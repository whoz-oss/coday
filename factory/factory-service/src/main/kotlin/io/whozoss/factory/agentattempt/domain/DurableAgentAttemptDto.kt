package io.whozoss.factory.agentattempt.domain

import java.time.Instant

/**
 * Bounded read model of a durable agent attempt exposed over the workflow HTTP
 * surface (Cockpit V2).
 *
 * It deliberately carries only the stable, operator-visible identity and
 * lifecycle fields. All execution secrets and internal recovery/lease data are
 * excluded: `ownerToken`, `capabilityToken`, `commandId`, `brief`,
 * `leaseExpiresAt`, `lastObservedEventId` and `turnCorrelation` never cross the
 * public boundary. `status` is rendered as its stable persisted [AgentAttemptStatus.dbValue].
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
     * carry the turn brief, which never crosses the public boundary.
     */
    val expectedAmendmentSeq: Long? = null,
    val revision: Int,
    val createdAt: Instant,
    val startedAt: Instant? = null,
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
    completedAt = completedAt,
    terminal = status.terminal,
    sealingClass = AgentOsRuntimeStateMapping.classify(status).name,
)
