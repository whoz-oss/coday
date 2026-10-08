package io.whozoss.factory.agentattempt.persistence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapability
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.AgentStepResultLimits
import io.whozoss.factory.agentattempt.domain.AgentStepResultObservedIdentity
import io.whozoss.factory.agentattempt.domain.AgentStepResultRow
import io.whozoss.factory.agentattempt.domain.AgentStepResultStatus
import io.whozoss.factory.agentattempt.domain.AgentStepResultSubmitted
import io.whozoss.factory.agentattempt.domain.AgentStepResultValidation
import io.whozoss.factory.agentattempt.domain.CanonicalJsonHash
import io.whozoss.factory.agentattempt.domain.InvalidResultCapabilityIdentityException
import io.whozoss.factory.agentattempt.domain.IssuedCapability
import io.whozoss.factory.agentattempt.domain.ResultCapabilityAlreadyIssuedException
import io.whozoss.factory.agentattempt.domain.ResultCapabilityExpiredException
import io.whozoss.factory.agentattempt.domain.ResultCapabilityIdentityConflictException
import io.whozoss.factory.agentattempt.domain.ResultCapabilityInvalidException
import io.whozoss.factory.agentattempt.domain.ResultAttemptNotRefreshableException
import io.whozoss.factory.agentattempt.domain.ResultIdentityMismatchException
import io.whozoss.factory.agentattempt.domain.ResultSemanticCollisionException
import io.whozoss.factory.agentattempt.domain.StaleAmendmentSequenceException
import io.whozoss.factory.agentattempt.domain.SubmitOutcome
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import mu.KotlinLogging
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Neo4j implementation of [AgentStepResultRepository].
 *
 * Replaces `JdbcAgentStepResultRepository`. The result write, the attempt
 * terminalization and the `result_submitted` outbox event are committed in a
 * single Neo4j transaction.
 *
 * ## Capability persistence
 *
 * The former V6 composite foreign key `result_capabilities -> agent_step_results`
 * has no graph equivalent, but the write order is preserved for behavioural
 * parity: [issue] writes a **reservation** result row (status
 * `collision_detected`, payload `type = capability-reserved`) together with the
 * capability, and [submit] updates that same node in place with the
 * authoritative submitted payload.
 *
 * The clear bearer token is never stored; only its `sha256:<hex>` digest is,
 * denormalised onto [ResultCapabilityNode.tokenHash] for an indexed lookup. The
 * digest is still compared in constant time after the fetch.
 */
@Repository
class Neo4jAgentStepResultRepository(
    private val results: SpringDataNeo4jAgentStepResultRepository,
    private val capabilities: SpringDataNeo4jResultCapabilityRepository,
    private val outbox: SpringDataNeo4jOutboxRepository,
    private val attempts: AgentStepAttemptRepository,
    private val objectMapper: ObjectMapper,
    /**
     * Read port of the workflow instance used to enforce the authoritative
     * amendment counter compare-and-set (Lot E). Optional so a hand-assembled
     * persistence stack (restart tests) can omit it; when absent an
     * `expected_amendment_seq` cannot be validated and is simply ignored.
     */
    private val workflowRepository: WorkflowRepository? = null,
) : AgentStepResultRepository {

    private val logger = KotlinLogging.logger {}

    @Transactional
    override fun issue(
        scope: TenantScope,
        identity: AgentStepResultCapabilityIdentity,
        now: Instant,
        ttlSeconds: Long,
    ): IssuedCapability {
        validateIdentity(identity)
        if (!attempts.exists(scope, identity.namespaceId, identity.workflowId, identity.stepId, identity.attemptId)) {
            throw InvalidResultCapabilityIdentityException(
                "No agent step attempt '${identity.attemptId}' exists in this scope",
            )
        }
        val existing = findCapabilityForAttempt(scope, identity)
        if (existing != null) {
            val same = existing.attemptId == identity.attemptId &&
                existing.workflowId == identity.workflowId &&
                existing.stepId == identity.stepId &&
                existing.namespaceId == identity.namespaceId &&
                existing.caseId == identity.caseId &&
                existing.agentName == identity.agentName &&
                existing.briefHash == identity.briefHash
            if (same) throw ResultCapabilityAlreadyIssuedException(
                "A capability was already issued for attempt '${identity.attemptId}'",
            )
            throw ResultCapabilityIdentityConflictException(
                "A capability already exists for attempt '${identity.attemptId}' with a different identity",
            )
        }

        val token = newToken()
        val tokenHash = CanonicalJsonHash.sha256(token)
        val resultId = UUID.randomUUID().toString()
        val capabilityId = UUID.randomUUID().toString()
        val issuedAt = now.toString()
        val expiresAt = now.plusSeconds(ttlSeconds).toString()
        val capability = AgentStepResultCapability(
            type = CAPABILITY_ISSUED_TYPE,
            capabilityId = capabilityId,
            tokenHash = tokenHash,
            attemptId = identity.attemptId,
            workflowId = identity.workflowId,
            stepId = identity.stepId,
            namespaceId = identity.namespaceId,
            caseId = identity.caseId,
            agentName = identity.agentName,
            briefHash = identity.briefHash,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            submissionBudget = AgentStepResultLimits.SUBMISSION_BUDGET,
        )

        insertReservation(scope, identity, resultId, capabilityId, now)
        insertCapability(scope, resultId, capability, now)

        return IssuedCapability(token = token, expiresAt = expiresAt)
    }

    @Transactional
    override fun refresh(
        scope: TenantScope,
        identity: AgentStepResultObservedIdentity,
        runtimeId: String,
        refreshKey: String,
        now: Instant,
        ttlSeconds: Long,
    ): IssuedCapability {
        val namespaceId = identity.namespaceId ?: throw ResultIdentityMismatchException()
        val attemptId = identity.attemptId ?: throw ResultIdentityMismatchException()
        val caseId = identity.caseId ?: throw ResultIdentityMismatchException()
        val agentName = identity.agentName ?: throw ResultIdentityMismatchException()
        val node = capabilities.findByNamespaceAndAttempt(
            scope.organizationId,
            scope.workstreamId,
            namespaceId,
            attemptId,
            CAPABILITY_TYPE,
        ) ?: throw ResultCapabilityInvalidException()
        val current = deserialize(node.payload, AgentStepResultCapability::class.java)
        if (current.caseId != caseId || current.agentName != agentName || runtimeId.isBlank()) {
            throw ResultIdentityMismatchException()
        }
        val result = findResult(scope, current)
        if (result == null || payloadType(result.payload) != RESERVATION_TYPE) {
            throw ResultAttemptNotRefreshableException()
        }
        if (refreshKey.isBlank()) throw ResultIdentityMismatchException()
        val attempt = attempts.find(scope, namespaceId, current.workflowId, current.stepId, attemptId)
        if (attempt == null || attempt.status in TERMINAL_REFRESH_STATUSES) {
            throw ResultAttemptNotRefreshableException()
        }
        val token = newToken()
        val renewed = current.copy(
            tokenHash = CanonicalJsonHash.sha256(token),
            issuedAt = now.toString(),
            expiresAt = now.plusSeconds(ttlSeconds).toString(),
            submissionBudget = AgentStepResultLimits.SUBMISSION_BUDGET,
        )
        val updated = capabilities.rotateIfRefreshable(
            id = node.id,
            resultId = AgentStepResultNode.compositeId(
                scope.organizationId, scope.workstreamId, current.namespaceId,
                current.workflowId, current.stepId, current.attemptId, node.resultId,
            ),
            expectedTokenHash = current.tokenHash,
            tokenHash = renewed.tokenHash,
            payload = serialize(renewed),
            createdAt = now,
        )
        if (updated != 1L) throw ResultAttemptNotRefreshableException("Capability was concurrently renewed or consumed")
        return IssuedCapability(token, renewed.expiresAt)
    }

    @Transactional
    override fun submit(
        scope: TenantScope,
        token: String,
        business: JsonNode,
        observed: AgentStepResultObservedIdentity,
        now: Instant,
    ): SubmitOutcome {
        val capability = findByTokenHash(scope, CanonicalJsonHash.sha256(token))
            ?: throw ResultCapabilityInvalidException()
        // Identity fencing: the submission is bound to exactly the attempt,
        // case, agent and (when declared by the trust boundary) namespace the
        // capability was issued for. `workflowId`/`stepId` are never declared
        // by the caller — they are taken from the capability itself — and the
        // tenant `(organizationId, workstreamId)` is fenced by the token-hash
        // lookup being scoped to the verified `TenantScope`: a token presented
        // under another tenant simply does not resolve (RESULT_CAPABILITY_INVALID).
        if (observed.attemptId != capability.attemptId ||
            observed.caseId != capability.caseId ||
            observed.agentName != capability.agentName ||
            (observed.namespaceId != null && observed.namespaceId != capability.namespaceId)
        ) {
            throw ResultIdentityMismatchException()
        }

        val resultHash = CanonicalJsonHash.hash(business)
        val existing = findResult(scope, capability)
        if (existing != null && payloadType(existing.payload) == SUBMITTED_TYPE) {
            val submitted = deserialize(existing.payload, AgentStepResultSubmitted::class.java)
            return if (existing.semanticSignature == resultHash) {
                SubmitOutcome.Replayed(submitted)
            } else {
                throw ResultSemanticCollisionException()
            }
        }

        if (now.isAfter(Instant.parse(capability.expiresAt))) throw ResultCapabilityExpiredException()

        val parsed = AgentStepResultValidation.parseBusiness(business)
        validateAmendmentSequence(scope, capability, parsed)
        val submitted = AgentStepResultSubmitted(
            type = SUBMITTED_TYPE,
            resultId = existing?.resultId ?: UUID.randomUUID().toString(),
            attemptId = capability.attemptId,
            workflowId = capability.workflowId,
            stepId = capability.stepId,
            namespaceId = capability.namespaceId,
            caseId = capability.caseId,
            agentName = capability.agentName,
            briefHash = capability.briefHash,
            status = parsed.status,
            summary = parsed.summary,
            artifacts = parsed.artifacts,
            claims = parsed.claims,
            findings = parsed.findings,
            submittedAt = now.toString(),
            resultHash = resultHash,
            expectedAmendmentSeq = parsed.expectedAmendmentSeq,
        )
        val payload = serialize(submitted)
        val resultStatus = DB_RESULT_STATUS.getValue(parsed.status)
        if (existing != null) {
            updateResult(scope, capability, existing.resultId, resultStatus, resultHash, payload)
        } else {
            insertSubmitted(scope, capability, submitted.resultId, resultStatus, resultHash, payload, now)
        }

        // A NEEDS_RESEARCH verdict is NOT a terminal failure: the step is blocked
        // and will be re-armed as a brand-new attempt on the same worktree once a
        // Searcher attempt has filled the gap. The result-channel attempt is
        // therefore left non-terminal (the submitted result row is authoritative)
        // and is never sealed as `failed`.
        val attemptTerminalStatus = ATTEMPT_TERMINAL_STATUS[parsed.status]
        if (attemptTerminalStatus != null) {
            val terminalized = attempts.terminalize(
                scope,
                capability.namespaceId,
                capability.workflowId,
                capability.stepId,
                capability.attemptId,
                attemptTerminalStatus,
            )
            if (terminalized == 0) {
                // Phase 10 late-result policy: the attempt is already sealed in a
                // terminal status. Do NOT throw and never rewrite the verdict —
                // the sealed attempt stays byte-for-byte unchanged and the result
                // row above remains governed by the semantic-collision guard (an
                // identical late submission replays, a divergent one was already
                // rejected before reaching this point). Observed for audit only.
                logger.warn {
                    "Late result ignored for sealed attempt '${capability.attemptId}' " +
                        "(workflow '${capability.workflowId}', step '${capability.stepId}'): " +
                        "the terminal verdict is immutable and was left unchanged"
                }
            }
        } else {
            logger.info {
                "NEEDS_RESEARCH result '${submitted.resultId}' for attempt '${capability.attemptId}' " +
                    "is blocked (not terminal); the step stays open for Searcher re-arm"
            }
        }
        insertOutbox(scope, submitted, now)

        return SubmitOutcome.Created(submitted)
    }

    override fun getByAttempt(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): AgentStepResultSubmitted? {
        val row = findResultRow(scope, namespaceId, workflowId, stepId, attemptId) ?: return null
        if (payloadType(row.payload) != SUBMITTED_TYPE) return null
        return deserialize(row.payload, AgentStepResultSubmitted::class.java)
    }

    override fun findByToken(scope: TenantScope, token: String): AgentStepResultCapability? =
        findByTokenHash(scope, CanonicalJsonHash.sha256(token))

    override fun findSubmittedWithNonTerminalAttempt(): List<ScopedSubmittedResult> =
        results.findSubmittedWithNonTerminalAttempt().map { node ->
            ScopedSubmittedResult(TenantScope(node.organizationId, node.workstreamId), node.toDomain())
        }

    override fun findUnredeemedReservedCapabilities(): List<ScopedReservedCapability> =
        capabilities.findUnredeemedReserved().mapNotNull { node ->
            runCatching { deserialize(node.payload, AgentStepResultCapability::class.java) }.getOrNull()
                ?.let { ScopedReservedCapability(TenantScope(node.organizationId, node.workstreamId), it) }
        }

    // ------------------------------------------------------------------
    // Capability / result IO
    // ------------------------------------------------------------------

    private fun findCapabilityForAttempt(
        scope: TenantScope,
        identity: AgentStepResultCapabilityIdentity,
    ): AgentStepResultCapability? =
        capabilities
            .findByAttempt(
                scope.organizationId,
                scope.workstreamId,
                identity.namespaceId,
                identity.workflowId,
                identity.stepId,
                identity.attemptId,
                CAPABILITY_TYPE,
            )?.let { deserialize(it.payload, AgentStepResultCapability::class.java) }

    private fun findByTokenHash(scope: TenantScope, tokenHash: String): AgentStepResultCapability? {
        // The digest is indexed (denormalised on the node) for a fast lookup, but
        // it is still re-verified in constant time before the capability is
        // trusted — the same guarantee the former JSONB scan gave.
        val node = capabilities.findByTokenHash(scope.organizationId, scope.workstreamId, tokenHash) ?: return null
        val record = runCatching { deserialize(node.payload, AgentStepResultCapability::class.java) }.getOrNull()
            ?: return null
        return record.takeIf {
            it.type == CAPABILITY_ISSUED_TYPE && CanonicalJsonHash.safeEqual(it.tokenHash, tokenHash)
        }
    }

    private fun findResult(scope: TenantScope, capability: AgentStepResultCapability): AgentStepResultRow? =
        findResultRow(scope, capability.namespaceId, capability.workflowId, capability.stepId, capability.attemptId)

    private fun findResultRow(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): AgentStepResultRow? =
        results
            .findFirstByAttempt(
                scope.organizationId,
                scope.workstreamId,
                namespaceId,
                workflowId,
                stepId,
                attemptId,
            )?.toDomain()

    private fun insertReservation(
        scope: TenantScope,
        identity: AgentStepResultCapabilityIdentity,
        resultId: String,
        capabilityId: String,
        now: Instant,
    ) {
        results.save(
            AgentStepResultNode(
                id = AgentStepResultNode.compositeId(
                    scope.organizationId,
                    scope.workstreamId,
                    identity.namespaceId,
                    identity.workflowId,
                    identity.stepId,
                    identity.attemptId,
                    resultId,
                ),
                organizationId = scope.organizationId,
                workstreamId = scope.workstreamId,
                namespaceId = identity.namespaceId,
                workflowId = identity.workflowId,
                stepId = identity.stepId,
                attemptId = identity.attemptId,
                resultId = resultId,
                resultStatus = COLLISION_DB_STATUS,
                semanticSignature = null,
                payload = serialize(
                    ResultReservation(
                        type = RESERVATION_TYPE,
                        capabilityId = capabilityId,
                        resultId = resultId,
                        attemptId = identity.attemptId,
                    ),
                ),
                createdAt = now,
            ),
        )
    }

    private fun insertSubmitted(
        scope: TenantScope,
        capability: AgentStepResultCapability,
        resultId: String,
        resultStatus: String,
        resultHash: String,
        payload: String,
        now: Instant,
    ) {
        results.save(
            AgentStepResultNode(
                id = AgentStepResultNode.compositeId(
                    scope.organizationId,
                    scope.workstreamId,
                    capability.namespaceId,
                    capability.workflowId,
                    capability.stepId,
                    capability.attemptId,
                    resultId,
                ),
                organizationId = scope.organizationId,
                workstreamId = scope.workstreamId,
                namespaceId = capability.namespaceId,
                workflowId = capability.workflowId,
                stepId = capability.stepId,
                attemptId = capability.attemptId,
                resultId = resultId,
                resultStatus = resultStatus,
                semanticSignature = resultHash,
                payload = payload,
                createdAt = now,
            ),
        )
    }

    private fun updateResult(
        scope: TenantScope,
        capability: AgentStepResultCapability,
        resultId: String,
        resultStatus: String,
        resultHash: String,
        payload: String,
    ) {
        val id = AgentStepResultNode.compositeId(
            scope.organizationId,
            scope.workstreamId,
            capability.namespaceId,
            capability.workflowId,
            capability.stepId,
            capability.attemptId,
            resultId,
        )
        results.updateResult(id = id, resultStatus = resultStatus, semanticSignature = resultHash, payload = payload)
    }

    private fun insertCapability(
        scope: TenantScope,
        resultId: String,
        capability: AgentStepResultCapability,
        now: Instant,
    ) {
        capabilities.save(
            ResultCapabilityNode.fromDomain(
                scope = scope,
                resultId = resultId,
                capabilityType = CAPABILITY_TYPE,
                capability = capability,
                payload = serialize(capability),
                now = now,
            ),
        )
    }

    private fun insertOutbox(scope: TenantScope, submitted: AgentStepResultSubmitted, now: Instant) {
        val eventId = UUID.randomUUID().toString()
        val payload = objectMapper.createObjectNode().apply {
            put("aggregateType", "agent_step_result")
            put("attemptId", submitted.attemptId)
            put("resultId", submitted.resultId)
            put("status", submitted.status.name)
            // Continuation coordinates: the drain worker needs the destination
            // instance (and its scope) to advance the DAG once the result is
            // submitted, without re-deriving them from the attempt table.
            put("namespaceId", submitted.namespaceId)
            put("workflowId", submitted.workflowId)
            put("stepId", submitted.stepId)
            put("caseId", submitted.caseId)
        }
        outbox.save(
            OutboxEventNode(
                id = OutboxEventNode.compositeId(scope.organizationId, eventId),
                organizationId = scope.organizationId,
                eventId = eventId,
                workstreamId = scope.workstreamId,
                eventType = RESULT_SUBMITTED,
                payload = serialize(payload),
                status = PENDING,
                attempts = 0,
                createdAt = now,
                dispatchedAt = null,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun validateAmendmentSequence(
        scope: TenantScope,
        capability: AgentStepResultCapability,
        parsed: io.whozoss.factory.agentattempt.domain.AgentStepResultBusiness,
    ) {
        val expected = parsed.expectedAmendmentSeq ?: return
        val repository = workflowRepository ?: return
        val instance = repository.findInstance(scope, capability.namespaceId, capability.workflowId)
        val current = instance?.amendmentSeq ?: 0L
        if (expected != current) {
            throw StaleAmendmentSequenceException(
                "Result for attempt '${capability.attemptId}' declares expected_amendment_seq=$expected " +
                    "but the workflow '${capability.workflowId}' amendment sequence is $current",
                details = mapOf(
                    "workflowId" to capability.workflowId,
                    "expectedAmendmentSeq" to expected,
                    "currentAmendmentSeq" to current,
                ),
            )
        }
    }

    private fun validateIdentity(identity: AgentStepResultCapabilityIdentity) {
        val fields = listOf(
            identity.attemptId,
            identity.workflowId,
            identity.stepId,
            identity.namespaceId,
            identity.caseId,
            identity.agentName,
        )
        if (fields.any { !CanonicalJsonHash.isSafeId(it) } || !CanonicalJsonHash.isBriefHash(identity.briefHash)) {
            throw InvalidResultCapabilityIdentityException()
        }
    }

    private fun payloadType(payload: String): String? =
        runCatching { objectMapper.readTree(payload).get("type")?.asText() }.getOrNull()

    private fun serialize(value: Any): String = objectMapper.writeValueAsString(value)

    private fun <T> deserialize(payload: String, type: Class<T>): T = objectMapper.readValue(payload, type)

    private fun newToken(): String {
        val bytes = ByteArray(32)
        SECURE_RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Reservation payload persisted inside an `:AgentStepResult` node at issue time. */
    private data class ResultReservation(
        val type: String,
        val capabilityId: String,
        val resultId: String,
        val attemptId: String,
    )

    private companion object {
        val SECURE_RANDOM = SecureRandom()
        const val CAPABILITY_TYPE = "agent_step_submit"
        const val CAPABILITY_ISSUED_TYPE = "capability-issued"
        const val RESERVATION_TYPE = "capability-reserved"
        const val SUBMITTED_TYPE = "result-submitted"
        const val COLLISION_DB_STATUS = "collision_detected"
        const val RESULT_SUBMITTED = "result_submitted"
        const val PENDING = "pending"
        val TERMINAL_REFRESH_STATUSES = setOf("completed", "failed", "interrupted", "indeterminate", "superseded")

        val DB_RESULT_STATUS: Map<AgentStepResultStatus, String> = mapOf(
            AgentStepResultStatus.PASS to "success",
            AgentStepResultStatus.FAIL to "failure",
            AgentStepResultStatus.NEEDS_RESEARCH to "needs_research",
        )
        val ATTEMPT_TERMINAL_STATUS: Map<AgentStepResultStatus, String> = mapOf(
            AgentStepResultStatus.PASS to "completed",
            AgentStepResultStatus.FAIL to "failed",
        )
    }
}
