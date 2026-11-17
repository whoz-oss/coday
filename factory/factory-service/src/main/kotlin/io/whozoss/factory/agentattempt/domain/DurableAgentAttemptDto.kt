package io.whozoss.factory.agentattempt.domain

import java.time.Instant

/**
 * Bounded read model of a durable agent attempt exposed over the workflow HTTP
 * surface (Factory Cockpit).
 *
 * It deliberately carries only the stable, operator-visible identity and
 * lifecycle fields. All execution secrets and internal recovery/lease data are
 * excluded: `ownerToken`, `capabilityToken`, `commandId`,
 * `leaseExpiresAt`, `lastObservedEventId` and `turnCorrelation` never cross the
 * public boundary. `status` is rendered as its stable persisted [AgentAttemptStatus.dbValue].
 *
 * Access decision: GET /api/factory/workflows/{id}/attempts exposes the dispatched
 * instructions for operator audit through authenticated, organization-scoped reads.
 * resolveWorkflowCaller requires a namespace and resolves its workstream within the
 * authenticated organization; persistence then scopes by organization/workstream/namespace.
 * The same DTO is used by the authenticated workstream-service read facade.
 * This is tenant business content, NOT public/anonymous data. The current resolver
 * does not enforce a separate per-user namespace-membership ACL; this DTO adds no
 * broader read access than the existing attempt endpoints. The rendered brief
 * contains the controller request, step contract, upstream handoff and public
 * attempt/submission IDs. Factory bearer/owner tokens are passed separately to the
 * runtime, never interpolated into the brief; the raw contextEnvelope stays private.
 * Defense in depth redacts known execution tokens and credential-shaped text on read.
 */
data class DurableAgentAttemptDto(
    val attemptId: String,
    val stepId: String,
    val attemptNumber: Int,
    val agentName: String,
    val status: String,
    val caseId: String,
    val failureCode: String? = null,
    val resultEvidenceId: String? = null,
    /** Work environment the attempt ran against (operator metadata, never a secret). */
    val environmentRef: String? = null,
    /** Environment revision the attempt was bound to at reservation time. */
    val expectedEnvironmentRevision: Int? = null,
    /**
     * Bounded resumption context of a successor attempt (Phase 4): the
     * question, the audited human answer/actor and the predecessor
     * `attemptId`/`interactionId` links, so Cockpit can display the full Q&A
     * chain of an attempt series. No secrets; `null` on first attempts.
     */
    val resumptionContext: String? = null,
    /**
     * The amendment sequence this attempt's context was frozen against
     * (Lot D schema field). Amendment logic itself belongs to Lot E.
     * The raw frozen context envelope is intentionally NOT exposed here: it can
     * carry internal recovery data. Only the dispatched mandate is exposed separately.
     */
    val expectedAmendmentSeq: Long? = null,
    val revision: Int,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    /** Closed RUNNING intervals only; add now-activeSince while status is running. */
    val activeDurationMs: Long = 0,
    val activeSince: Instant? = null,
    /** Closed WAITING_HUMAN intervals only; add now-waitingSince while waiting. */
    val waitingDurationMs: Long = 0,
    val waitingSince: Instant? = null,
    val completedAt: Instant? = null,
    /**
     * Phase 10 observability: `true` when [status] is a sealed terminal
     * verdict ([AgentAttemptStatus.terminal]). A terminal attempt is
     * immutable — a late result never changes the sealed verdict.
     */
    val terminal: Boolean = false,
    /**
     * Phase 10 observability: the sealing distinction
     * ([AgentOsRuntimeStateMapping.SealingClass] wire name) the cockpit uses
     * to separate a still-observed attempt (`ACTIVE`), an authoritatively
     * succeeded one (`COMPLETED`) and a runtime-closed one
     * (`RUNTIME_CLOSED`: failed / indeterminate / interrupted / superseded).
     */
    val sealingClass: String = AgentOsRuntimeStateMapping.SealingClass.ACTIVE.name,
    /** Dispatched instructions for authenticated tenant-scoped operator audit; redacted, null before dispatch. */
    val effectiveMandate: String? = null,
)

/** Map a domain [DurableAgentAttempt] to its bounded public read DTO. */
fun DurableAgentAttempt.toDto(): DurableAgentAttemptDto = DurableAgentAttemptDto(
    attemptId = attemptId,
    stepId = stepId,
    attemptNumber = attemptNumber,
    agentName = agentName,
    status = status.dbValue,
    caseId = caseId,
    failureCode = failureCode,
    resultEvidenceId = resultEvidenceId,
    environmentRef = environmentRef,
    expectedEnvironmentRevision = expectedEnvironmentRevision,
    resumptionContext = resumptionContext,
    expectedAmendmentSeq = expectedAmendmentSeq,
    revision = revision,
    createdAt = createdAt,
    startedAt = startedAt,
    activeDurationMs = activeDurationMs,
    activeSince = activeSince,
    waitingDurationMs = waitingDurationMs,
    waitingSince = waitingSince,
    completedAt = completedAt,
    terminal = status.terminal,
    sealingClass = AgentOsRuntimeStateMapping.classify(status).name,
    effectiveMandate = if (startedAt != null) redactedMandate() else null,
)

/** Defense in depth: historical/user/plugin instructions may accidentally carry credentials. */
private fun DurableAgentAttempt.redactedMandate(): String? {
    var text = brief ?: return null
    for (token in listOfNotNull(capabilityToken, ownerToken).filter { it.isNotBlank() }) {
        text = text.replace(token, "<redacted>")
    }
    return text
        .replace(Regex("(?i)\\b([A-Z0-9_]*(?:TOKEN|SECRET|PASSWORD|API_KEY|PRIVATE_KEY))\\s*=\\s*[^\\s]+"), "$1=<redacted>")
        .replace(Regex("(?i)\\b(bearer)\\s+[^\\s]+"), "$1 <redacted>")
}
