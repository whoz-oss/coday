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
    val revision: Int,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
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
    revision = revision,
    createdAt = createdAt,
    startedAt = startedAt,
    completedAt = completedAt,
)
