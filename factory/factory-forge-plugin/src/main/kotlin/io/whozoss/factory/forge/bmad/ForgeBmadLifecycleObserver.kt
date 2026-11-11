package io.whozoss.factory.forge.bmad

import io.whozoss.factory.forge.port.JiraClient
import io.whozoss.factory.sdk.spi.FactoryRunLifecycleObserver
import io.whozoss.factory.sdk.spi.FragmentOutcome
import io.whozoss.factory.sdk.spi.FragmentView
import io.whozoss.factory.sdk.spi.RunView
import org.pf4j.Extension
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fail-safe BMAD lifecycle notifications to Jira (transitions + audit comments).
 *
 * Notifications are deduplicated by `(kind, runId, fragmentId, status)` and Jira
 * failures never fail a Run. This observer performs no filesystem operations.
 * Sprint-status file synchronization is disabled: the plugin has no core port
 * providing both the authoritative Run workspace and D7 execution occupancy.
 * `repo_root` and `sprint_status_path` in domainState are therefore ignored;
 * `sprintStatusUpdated` remains false for event compatibility.
 */
@Extension
class ForgeBmadLifecycleObserver(
    @Autowired(required = false)
    private val jiraClient: JiraClient? = null,
) : FactoryRunLifecycleObserver {

    data class LifecycleEvent(
        val kind: String,
        val runId: String,
        val ticket: String?,
        val fragmentId: String? = null,
        val flowType: String? = null,
        val status: String,
        val jiraTransitionApplied: Boolean = false,
        val sprintStatusUpdated: Boolean = false,
    )

    private val recordedEvents = CopyOnWriteArrayList<LifecycleEvent>()
    private val processedKeys = CopyOnWriteArrayList<String>()

    fun events(): List<LifecycleEvent> = recordedEvents.toList()

    override fun onFragmentStarted(run: RunView, fragment: FragmentView) {
        val dedupKey = "fragment_started|${run.runId}|${fragment.fragmentId}|${fragment.status}"
        if (!processedKeys.addIfAbsent(dedupKey)) return

        val jiraApplied = syncJira(
            ticket = run.ticket,
            targetStatus = mapFlowToJiraStatus(fragment.flowType),
            comment = "[Factory Run ${run.runId}] Started fragment `${fragment.flowType}` (#${fragment.ordinal}).",
        )
        recordedEvents += LifecycleEvent(
            kind = "fragment_started",
            runId = run.runId,
            ticket = run.ticket,
            fragmentId = fragment.fragmentId,
            flowType = fragment.flowType,
            status = fragment.status,
            jiraTransitionApplied = jiraApplied,
        )
    }

    override fun onFragmentFinished(run: RunView, outcome: FragmentOutcome) {
        val dedupKey = "fragment_finished|${run.runId}|${outcome.fragmentId}|${outcome.status}"
        if (!processedKeys.addIfAbsent(dedupKey)) return

        recordedEvents += LifecycleEvent(
            kind = "fragment_finished",
            runId = run.runId,
            ticket = run.ticket,
            fragmentId = outcome.fragmentId,
            flowType = outcome.flowType,
            status = outcome.status,
            jiraTransitionApplied = false,
        )
    }

    override fun onRunFinished(run: RunView, status: String) {
        val dedupKey = "run_finished|${run.runId}||$status"
        if (!processedKeys.addIfAbsent(dedupKey)) return

        val targetJiraStatus = when (status.lowercase()) {
            "completed" -> "Done"
            "failed", "cancelled" -> "Blocked"
            else -> null
        }
        val jiraApplied = syncJira(
            ticket = run.ticket,
            targetStatus = targetJiraStatus,
            comment = "[Factory Run ${run.runId}] Run finished with status `$status` (domainRevision=${run.domainRevision}).",
        )
        recordedEvents += LifecycleEvent(
            kind = "run_finished",
            runId = run.runId,
            ticket = run.ticket,
            status = status,
            jiraTransitionApplied = jiraApplied,
        )
    }

    private fun syncJira(ticket: String?, targetStatus: String?, comment: String?): Boolean {
        val client = jiraClient ?: return false
        val ticketId = ticket?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (!client.isConfigured()) return false
        var transitioned = false
        if (targetStatus != null) {
            transitioned = runCatching { client.transitionTicket(ticketId, targetStatus) }.getOrDefault(false)
        }
        if (!comment.isNullOrBlank()) {
            runCatching { client.postComment(ticketId, comment) }
        }
        return transitioned
    }

    companion object {
        fun mapFlowToJiraStatus(flowType: String): String? = when (flowType) {
            ForgeBmadCatalogContributor.FLOW_PRODUCT_SPEC,
            ForgeBmadCatalogContributor.FLOW_PRODUCT_APPROVAL,
            ForgeBmadCatalogContributor.FLOW_ASSESSMENT,
            ForgeBmadCatalogContributor.FLOW_UX_DESIGN,
            ForgeBmadCatalogContributor.FLOW_TECH_DESIGN,
            ForgeBmadCatalogContributor.FLOW_SPEC_APPROVAL,
            -> "Specification"

            ForgeBmadCatalogContributor.FLOW_IMPLEMENT_STEP,
            ForgeBmadCatalogContributor.FLOW_CORRECTION,
            -> "In Progress"

            ForgeBmadCatalogContributor.FLOW_CODE_REVIEW -> "In Review"
            ForgeBmadCatalogContributor.FLOW_ACCEPTANCE -> "Acceptance"
            else -> null
        }
    }
}
