package io.whozoss.factory.agentattempt.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapability
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.AgentStepResultObservedIdentity
import io.whozoss.factory.agentattempt.domain.AgentStepResultValidation
import io.whozoss.factory.agentattempt.domain.CanonicalJsonHash
import io.whozoss.factory.agentattempt.domain.IdempotencyKeyCollisionException
import io.whozoss.factory.agentattempt.domain.IssuedCapability
import io.whozoss.factory.agentattempt.domain.ResultSchemaInvalidException
import io.whozoss.factory.agentattempt.domain.SubmitOutcome
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.persistence.AgentStepResultRepository
import io.whozoss.factory.agentattempt.persistence.IdempotencyRepository
import io.whozoss.factory.persistence.TenantScope
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Summary of one result-channel startup reconciliation pass. */
data class ResultChannelRecoveryReport(
    /** Submitted results whose non-terminal attempt was terminalized. */
    val submittedFinalized: Int,
    /**
     * Expired capabilities still waiting on an unredeemed reservation. Their
     * attempts are deliberately left to the durable-attempt recovery: the
     * result channel never fabricates an outcome.
     */
    val expiredReservations: Int,
)

/** Canonical response payload of a capability-backed result submission. */
data class AgentStepResultSubmission(
    val resultId: String,
    val idempotent: Boolean,
    val resultHash: String,
) {
    /** `true` for a freshly created result (HTTP 201) and `false` on a replay (HTTP 200). */
    val created: Boolean
        get() = !idempotent
}

/**
 * Application service of the AGENT-STEP aggregate.
 *
 * Owns capability issuance, the idempotency-key layer over the V4
 * `idempotency_records` table and the submission transaction. The result write,
 * the attempt terminalization and the `result_submitted` outbox event are
 * committed in a single `@Transactional` boundary by the repository.
 */
@Service
class AgentStepResultService(
    private val results: AgentStepResultRepository,
    private val idempotency: IdempotencyRepository,
    private val attempts: AgentStepAttemptRepository,
    private val objectMapper: ObjectMapper,
) {

    private val logger = KotlinLogging.logger {}

    /** Issue a single-use capability bound to an attempt identity. */
    @Transactional
    fun issue(
        scope: TenantScope,
        identity: AgentStepResultCapabilityIdentity,
        now: Instant = Instant.now(),
        ttlSeconds: Long = 15L * 60L,
    ): IssuedCapability = results.issue(scope, identity, now, ttlSeconds)

    /**
     * Resolve the binding carried by a bearer token without redeeming it. Used
     * by the step-result binding endpoint to verify a capability and expose the
     * attempt identity it is bound to. Returns `null` for an unknown token.
     */
    @Transactional(readOnly = true)
    fun resolveCapability(scope: TenantScope, token: String): AgentStepResultCapability? =
        results.findByToken(scope, token)

    /** Authoritative terminal result accepted for this exact durable attempt. */
    @Transactional(readOnly = true)
    fun acceptedResult(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ) = results.getByAttempt(scope, namespaceId, workflowId, stepId, attemptId)

    @Transactional
    fun refresh(
        scope: TenantScope,
        observed: AgentStepResultObservedIdentity,
        runtimeId: String,
        refreshKey: String,
        now: Instant = Instant.now(),
        ttlSeconds: Long = io.whozoss.factory.agentattempt.domain.AgentStepResultLimits.CAPABILITY_TTL_SECONDS,
    ): IssuedCapability = results.refresh(scope, observed, runtimeId, refreshKey, now, ttlSeconds)

    /**
     * Redeem a capability with a structured business result.
     *
     * The optional [idempotencyKey] (typically `X-Idempotency-Key`) makes the
     * call replay-safe: an identical replay returns the cached response, a
     * divergent replay fails with `IDEMPOTENCY_KEY_COLLISION`.
     */
    @Transactional
    fun submit(
        scope: TenantScope,
        token: String,
        business: JsonNode?,
        observed: AgentStepResultObservedIdentity,
        idempotencyKey: String?,
        now: Instant = Instant.now(),
    ): AgentStepResultSubmission {
        val requestHash = idempotencyKey?.let { hashRequest(observed, business) }

        if (idempotencyKey != null) {
            val existing = idempotency.find(scope, idempotencyKey)
            if (existing != null) {
                if (existing.requestHash != requestHash) throw IdempotencyKeyCollisionException()
                return cachedSubmission(existing.responsePayload)
            }
        }

        if (!AgentStepResultValidation.validateBusiness(business)) throw ResultSchemaInvalidException()

        val outcome = results.submit(scope, token, business!!, observed, now)
        val submission = outcome.toSubmission()
        if (idempotencyKey != null) {
            idempotency.save(scope, idempotencyKey, requestHash!!, cachedPayload(submission))
        }
        return submission
    }

    private fun SubmitOutcome.toSubmission(): AgentStepResultSubmission =
        AgentStepResultSubmission(
            resultId = result.resultId,
            idempotent = idempotent,
            resultHash = result.resultHash,
        )

    /**
     * SHA-256 hex of the canonical request (attempt identity + business result).
     * The trusted namespace observed at the HTTP boundary is part of the hash so
     * a replayed idempotency key under a different trusted namespace is a
     * collision, never a silent reuse.
     */
    private fun hashRequest(observed: AgentStepResultObservedIdentity, business: JsonNode?): String {
        val request = objectMapper.createObjectNode().apply {
            put("attemptId", observed.attemptId)
            put("caseId", observed.caseId)
            put("agentName", observed.agentName)
            put("namespaceId", observed.namespaceId)
            set<JsonNode>("result", business ?: objectMapper.nullNode())
        }
        return CanonicalJsonHash.sha256Hex(CanonicalJsonHash.canonicalJson(request))
    }

    private fun cachedPayload(submission: AgentStepResultSubmission): String =
        objectMapper.createObjectNode().apply {
            put("resultId", submission.resultId)
            put("resultHash", submission.resultHash)
        }.let(objectMapper::writeValueAsString)

    private fun cachedSubmission(payload: String): AgentStepResultSubmission {
        val node = objectMapper.readTree(payload)
        return AgentStepResultSubmission(
            resultId = node.path("resultId").asText(),
            idempotent = true,
            resultHash = node.path("resultHash").asText(),
        )
    }

    /**
     * Startup reconciliation of the result channel (see
     * [ResultChannelRecoveryWorker]). Two defensive sweeps, neither of which
     * ever fabricates a success:
     *
     *  1. **Submitted but not terminal** — a `result-submitted` row exists but
     *     the attempt was left non-terminal (a crash inside the commit window,
     *     should the atomic write ever be split): the attempt is terminalized
     *     in the state coherent with the submitted result
     *     (`success → completed`, `failure → failed`). A terminal attempt is
     *     immutable and is never touched.
     *  2. **Expired and unredeemed** — a capability past `expiresAt` still
     *     waiting on its reservation: it is observed and counted (submission
     *     is already refused by the expiry check), but its attempt is left to
     *     the durable-attempt recovery — no outcome is invented and no data
     *     is deleted.
     *
     * The pass is idempotent: replaying it changes nothing.
     */
    fun reconcileOnStartup(now: Instant = Instant.now()): ResultChannelRecoveryReport {
        var finalized = 0
        for (candidate in results.findSubmittedWithNonTerminalAttempt()) {
            val row = candidate.result
            val terminalStatus = when (row.resultStatus) {
                SUBMITTED_SUCCESS -> ATTEMPT_COMPLETED
                SUBMITTED_FAILURE -> ATTEMPT_FAILED
                else -> continue
            }
            // Defensive immutability re-check under the repository fence: a
            // terminal attempt is never mutated, even by reconciliation.
            val attempt = attempts.find(candidate.scope, row.namespaceId, row.workflowId, row.stepId, row.attemptId)
            if (attempt == null || attempt.status in TERMINAL_ATTEMPT_STATUSES) continue
            val updated = attempts.terminalize(
                candidate.scope,
                row.namespaceId,
                row.workflowId,
                row.stepId,
                row.attemptId,
                terminalStatus,
            )
            if (updated > 0) {
                finalized++
                logger.info {
                    "Result channel reconciliation terminalized attempt '${row.attemptId}' as '$terminalStatus' " +
                        "from its submitted result"
                }
            }
        }

        var expired = 0
        for (reserved in results.findUnredeemedReservedCapabilities()) {
            val capability = reserved.capability
            if (now.isAfter(Instant.parse(capability.expiresAt))) {
                expired++
                logger.info {
                    "Result capability '${capability.capabilityId}' of attempt '${capability.attemptId}' expired " +
                        "without a submitted result; the attempt is left to the durable-attempt recovery"
                }
            }
        }
        return ResultChannelRecoveryReport(submittedFinalized = finalized, expiredReservations = expired)
    }

    private companion object {
        const val SUBMITTED_SUCCESS = "success"
        const val SUBMITTED_FAILURE = "failure"
        const val ATTEMPT_COMPLETED = "completed"
        const val ATTEMPT_FAILED = "failed"
        val TERMINAL_ATTEMPT_STATUSES = setOf(ATTEMPT_COMPLETED, ATTEMPT_FAILED)
    }
}
