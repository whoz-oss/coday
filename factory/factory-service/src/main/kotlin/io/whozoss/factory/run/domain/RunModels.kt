package io.whozoss.factory.run.domain

import io.whozoss.factory.sdk.spi.FindingResolution
import io.whozoss.factory.sdk.spi.FindingSeverity
import io.whozoss.factory.sdk.spi.FindingStatus
import io.whozoss.factory.sdk.spi.FindingView
import io.whozoss.factory.sdk.spi.FlowDecision
import io.whozoss.factory.sdk.spi.FragmentOutcome
import io.whozoss.factory.sdk.spi.FragmentView
import io.whozoss.factory.sdk.spi.NewFinding
import io.whozoss.factory.sdk.spi.RunUpdate
import io.whozoss.factory.sdk.spi.RunView
import java.time.Instant
import java.util.UUID

/**
 * Allowed lifecycle statuses of a parent [RunRecord].
 */
object RunStatuses {
    const val RUNNING = "running"
    const val WAITING_HUMAN = "waiting_human"
    const val WAITING_HUMAN_ESCALATION = "waiting_human_escalation"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    val TERMINAL = setOf(COMPLETED, FAILED, CANCELLED)

    fun isTerminal(status: String?): Boolean = status != null && status in TERMINAL
}

/**
 * Persisted finding on a parent Run (D10: findings persist across revisions
 * until explicitly resolved, superseded, or marked won't-fix).
 */
data class RunFindingRecord(
    val id: String,
    val code: String,
    val severity: FindingSeverity,
    val status: FindingStatus,
    val message: String,
    val sourceFragmentId: String? = null,
    val stepId: String? = null,
    val filePath: String? = null,
    val openedAtRevision: Long = 0L,
    val resolutionReason: String? = null,
    val details: Map<String, Any?> = emptyMap(),
) {
    fun toView(): FindingView = FindingView(
        id = id,
        code = code,
        severity = severity,
        status = status,
        message = message,
        sourceFragmentId = sourceFragmentId,
        stepId = stepId,
        filePath = filePath,
        openedAtRevision = openedAtRevision,
        resolutionReason = resolutionReason,
        details = details,
    )
}

/**
 * Persisted fragment entry in a parent Run's append-only history (D3).
 */
data class RunFragmentRecord(
    val fragmentId: String,
    val ordinal: Int,
    val flowType: String,
    val status: String,
    val inputs: Map<String, Any?> = emptyMap(),
    val decisionReason: String? = null,
    val loopKey: String? = null,
    val createdAt: String = Instant.now().toString(),
    val completedAt: String? = null,
) {
    fun toView(): FragmentView = FragmentView(
        fragmentId = fragmentId,
        ordinal = ordinal,
        flowType = flowType,
        status = status,
        inputs = inputs,
        decisionReason = decisionReason,
    )
}

/**
 * Sealed decision recorded by the driving loop for a specific [decisionKey]
 * (`"start"` or a completed `fragmentId`), guaranteeing idempotency across
 * outbox replays and service restarts.
 */
data class RunDecisionRecord(
    val decisionKey: String,
    val decisionType: String,
    val flowType: String? = null,
    val fragmentId: String? = null,
    val reason: String,
    val loopKey: String? = null,
    val domainRevisionAfter: Long,
    val decidedAt: String = Instant.now().toString(),
)

/**
 * Active run-level human escalation opened when the selector returns
 * [FlowDecision.EscalateHuman] or when a loop bound is exceeded (§4.3, §4.4).
 */
data class RunEscalationRecord(
    val escalationId: String,
    val question: String,
    val options: List<String>,
    val reason: String,
    val status: String = "waiting", // waiting | closed
    val selectedOption: String? = null,
    val responseText: String? = null,
    val actorId: String? = null,
    val openedAt: String = Instant.now().toString(),
    val closedAt: String? = null,
)

/**
 * Authoritative parent Run aggregate (D2, §4.1).
 *
 * Owns the shared AgentOS case family ([rootCaseId]), the shared worktree,
 * the plugin-owned [domainState] fenced by [domainRevision], the persistent
 * [findings], the append-only [fragments] chain, [loopCounts] and the
 * idempotent [decisions] log.
 */
data class RunRecord(
    val namespaceId: String,
    val runId: String,
    val runType: String,
    val selectorVersion: String,
    val title: String,
    val ticket: String? = null,
    val status: String = RunStatuses.RUNNING,
    val revision: Int = 1,
    val domainState: Map<String, Any?> = emptyMap(),
    val domainRevision: Long = 0L,
    val amendmentSeq: Long = 0L,
    val rootCaseId: String? = null,
    val findings: List<RunFindingRecord> = emptyList(),
    val fragments: List<RunFragmentRecord> = emptyList(),
    val loopCounts: Map<String, Int> = emptyMap(),
    val decisions: Map<String, RunDecisionRecord> = emptyMap(),
    val flowVersions: Map<String, String> = emptyMap(),
    val flowDefinitionHashes: Map<String, String> = emptyMap(),
    val appliedValidationUpdates: Set<String> = emptySet(),
    val activeEscalation: RunEscalationRecord? = null,
    val maxTotalFragments: Int = DEFAULT_MAX_TOTAL_FRAGMENTS,
) {
    fun toView(): RunView = RunView(
        runId = runId,
        runType = runType,
        selectorVersion = selectorVersion,
        ticket = ticket,
        domainState = domainState,
        domainRevision = domainRevision,
        findings = findings.map { it.toView() },
        fragments = fragments.map { it.toView() },
        loopCounts = loopCounts,
    )

    /**
     * Pure application of a [RunUpdate] (from `selector.start` or `selector.reduce`)
     * onto this [RunRecord].
     *
     * Note on D10: advancing [domainRevision] never auto-closes open findings;
     * only explicit entries in [RunUpdate.findingResolutions] transition a
     * finding's status.
     */
    fun applyUpdate(update: RunUpdate, sourceFragmentId: String? = null): RunRecord {
        val nextDomainState = update.newDomainState ?: domainState
        val nextDomainRevision = if (update.newDomainState != null) domainRevision + 1 else domainRevision
        val nextAmendmentSeq = if (update.newDomainState != null) amendmentSeq + 1 else amendmentSeq

        val updatedExistingFindings = findings.map { existing ->
            val resolution: FindingResolution? = update.findingResolutions[existing.id]
            if (resolution != null) {
                existing.copy(
                    status = resolution.status,
                    resolutionReason = resolution.reason,
                )
            } else {
                existing
            }
        }

        val createdFindings = update.addedFindings.map { nf: NewFinding ->
            RunFindingRecord(
                id = nf.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                code = nf.code,
                severity = nf.severity,
                status = FindingStatus.OPEN,
                message = nf.message,
                sourceFragmentId = sourceFragmentId,
                stepId = nf.stepId,
                filePath = nf.filePath,
                openedAtRevision = nextDomainRevision,
                details = nf.details,
            )
        }

        return copy(
            domainState = nextDomainState,
            domainRevision = nextDomainRevision,
            amendmentSeq = nextAmendmentSeq,
            findings = updatedExistingFindings + createdFindings,
        )
    }

    companion object {
        const val DEFAULT_MAX_TOTAL_FRAGMENTS = 30
    }
}
