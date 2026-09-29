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
import io.whozoss.factory.agentattempt.persistence.AgentStepResultRepository
import io.whozoss.factory.agentattempt.persistence.IdempotencyRepository
import io.whozoss.factory.persistence.TenantScope
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

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
    private val objectMapper: ObjectMapper,
) {

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

    /** SHA-256 hex of the canonical request (attempt identity + business result). */
    private fun hashRequest(observed: AgentStepResultObservedIdentity, business: JsonNode?): String {
        val request = objectMapper.createObjectNode().apply {
            put("attemptId", observed.attemptId)
            put("caseId", observed.caseId)
            put("agentName", observed.agentName)
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
}
