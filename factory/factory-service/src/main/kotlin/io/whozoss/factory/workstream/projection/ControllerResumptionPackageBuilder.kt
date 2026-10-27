package io.whozoss.factory.workstream.projection

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workstream.domain.ControllerCaseBounds
import io.whozoss.factory.workstream.web.ContextBlocker
import io.whozoss.factory.workstream.web.ContextChange
import io.whozoss.factory.workstream.web.ContextHumanAction
import io.whozoss.factory.workstream.web.ContextWorkflow
import io.whozoss.factory.workstream.web.ControllerContextCounts
import io.whozoss.factory.workstream.web.ControllerResumptionPackage
import io.whozoss.factory.workstream.web.WorkstreamProjectionResponse
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/**
 * Builds the bounded resumption context package ("paquet de reprise borné")
 * of a controller case from the Phase 5 aggregated projection (Phase 9).
 *
 * The projection sections are already bounded by
 * [io.whozoss.factory.workstream.web.WorkstreamBounds]; this builder re-caps
 * them to the tighter [ControllerCaseBounds] and summarizes only the key
 * active workflows, open human interactions, blockers and recent changes. It
 * NEVER injects raw conversation histories — the package is a compact,
 * secret-free summary, and [toBoundedJson] enforces the hard
 * [ControllerCaseBounds.MAX_CONTEXT_SUMMARY_BYTES] cap on its serialized
 * form.
 */
@Component
class ControllerResumptionPackageBuilder(
    private val objectMapper: ObjectMapper,
) {

    /**
     * Map the already-bounded [projection] into the compact resumption
     * package: capped sections plus the authoritative (uncapped) section
     * counts and the projection `workstreamRevision` as [ControllerResumptionPackage.sourceRevision].
     */
    fun build(projection: WorkstreamProjectionResponse): ControllerResumptionPackage {
        val blockedSteps = projection.steps.items
            .filter { it.status == WorkflowStatuses.BLOCKED }
            .take(ControllerCaseBounds.MAX_BLOCKERS)
            .map { ContextBlocker(kind = "step", refId = it.stepId, workflowId = it.workflowId) }
        val failedOracles = projection.failedOracles.items
            .take((ControllerCaseBounds.MAX_BLOCKERS - blockedSteps.size).coerceAtLeast(0))
            .map { ContextBlocker(kind = "oracle", refId = it.executionId, workflowId = it.workflowId) }
        return ControllerResumptionPackage(
            workstreamId = projection.workstreamId,
            sourceRevision = projection.workstreamRevision,
            counts = ControllerContextCounts(
                activeWorkflows = projection.activeWorkflows.count,
                running = projection.steps.running,
                waitingHuman = projection.steps.waitingHuman,
                blocked = projection.steps.blocked,
                attempts = projection.attempts.count,
                humanActions = projection.humanActions.count,
                failedOracles = projection.failedOracles.count,
                environments = projection.environments.count,
            ),
            activeWorkflows = projection.activeWorkflows.items
                .take(ControllerCaseBounds.MAX_WORKFLOW_ITEMS)
                .map { ContextWorkflow(it.workflowId, it.workflowType, it.title, it.status) },
            openHumanInteractions = projection.humanActions.items
                .take(ControllerCaseBounds.MAX_HUMAN_ACTIONS)
                .map { ContextHumanAction(it.interactionId, it.workflowId, it.interactionType, it.status) },
            blockers = blockedSteps + failedOracles,
            recentChanges = projection.recentChanges.items
                .take(ControllerCaseBounds.MAX_RECENT_CHANGES)
                .map { ContextChange(it.kind, it.refId, it.workflowId, it.timestamp) },
        )
    }

    /**
     * Serialize [pkg] to compact JSON within the hard
     * [ControllerCaseBounds.MAX_CONTEXT_SUMMARY_BYTES] UTF-8 cap.
     *
     * Compact-or-reject (precedent: `AgentStepQuestionService.resumptionContext`):
     * when the full package would exceed the bound, the lowest-priority
     * sections are dropped progressively — `recentChanges`, then `blockers`,
     * then `openHumanInteractions`, then `activeWorkflows` — while `counts`
     * and `sourceRevision` are always kept. The minimal form is guaranteed to
     * fit; an overrun there is a programming error.
     */
    fun toBoundedJson(pkg: ControllerResumptionPackage): String {
        val candidates = listOf(
            pkg,
            pkg.copy(recentChanges = emptyList()),
            pkg.copy(recentChanges = emptyList(), blockers = emptyList()),
            pkg.copy(recentChanges = emptyList(), blockers = emptyList(), openHumanInteractions = emptyList()),
            pkg.copy(
                recentChanges = emptyList(),
                blockers = emptyList(),
                openHumanInteractions = emptyList(),
                activeWorkflows = emptyList(),
            ),
        )
        for (candidate in candidates) {
            val json = objectMapper.writeValueAsString(candidate)
            if (json.toByteArray(StandardCharsets.UTF_8).size <= ControllerCaseBounds.MAX_CONTEXT_SUMMARY_BYTES) {
                return json
            }
        }
        throw IllegalStateException(
            "The controller resumption package exceeds ${ControllerCaseBounds.MAX_CONTEXT_SUMMARY_BYTES} bytes",
        )
    }
}
