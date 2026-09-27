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
import io.whozoss.factory.agentattempt.domain.ResultIdentityMismatchException
import io.whozoss.factory.agentattempt.domain.ResultSemanticCollisionException
import io.whozoss.factory.agentattempt.domain.SubmitOutcome
import io.whozoss.factory.persistence.TenantScope
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * `NamedParameterJdbcTemplate` implementation of [AgentStepResultRepository].
 *
 * Port of `sql-agent-step-result-repository.ts`. Every statement is
 * parameterized and constrained to the supplied [TenantScope].
 *
 * ## Capability persistence and the V6 composite foreign key
 *
 * The V6 `result_capabilities` table declares a composite foreign key onto
 * `agent_step_results`. A submission capability therefore cannot be persisted
 * before the attempt has a result row. To honour both the schema and the
 * "append-only capability" intent, [issue] writes the capability together with a
 * **reservation** result row (status `collision_detected`, payload
 * `type = capability-reserved`), which [submit] then updates in place with the
 * authoritative submitted payload. This keeps the capability, the result, the
 * attempt transition and the outbox event inside one transactional boundary
 * while never inserting an orphan capability.
 *
 * The clear bearer token is never stored; only its `sha256:<hex>` digest, kept
 * inside the JSONB payload, is.
 */
@Repository
class JdbcAgentStepResultRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val attempts: AgentStepAttemptRepository,
) : AgentStepResultRepository {

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
        val reservation = ResultReservation(
            type = RESERVATION_TYPE,
            capabilityId = capabilityId,
            resultId = resultId,
            attemptId = identity.attemptId,
        )

        insertReservation(scope, identity, resultId, serialize(reservation), now)
        insertCapability(scope, identity, resultId, capability, now)

        return IssuedCapability(token = token, expiresAt = expiresAt)
    }

    @Transactional
    override fun submit(
        scope: TenantScope,
        token: String,
        business: JsonNode,
        observed: AgentStepResultObservedIdentity,
        now: Instant,
    ): SubmitOutcome {
        val capability = findByToken(scope, CanonicalJsonHash.sha256(token))
            ?: throw ResultCapabilityInvalidException()
        if (observed.attemptId != capability.attemptId ||
            observed.caseId != capability.caseId ||
            observed.agentName != capability.agentName
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
        )
        val payload = serialize(submitted)
        val resultStatus = DB_RESULT_STATUS.getValue(parsed.status)
        if (existing != null) {
            updateResult(scope, capability, existing.resultId, resultStatus, resultHash, payload)
        } else {
            insertSubmitted(scope, capability, submitted.resultId, resultStatus, resultHash, payload, now)
        }

        attempts.terminalize(
            scope,
            capability.namespaceId,
            capability.workflowId,
            capability.stepId,
            capability.attemptId,
            ATTEMPT_TERMINAL_STATUS.getValue(parsed.status),
        )
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

    // ------------------------------------------------------------------
    // Capability / result IO
    // ------------------------------------------------------------------

    private fun findCapabilityForAttempt(
        scope: TenantScope,
        identity: AgentStepResultCapabilityIdentity,
    ): AgentStepResultCapability? =
        jdbc.query(
            """
            SELECT payload FROM result_capabilities
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId
               AND workflow_id = :workflowId
               AND step_id = :stepId
               AND attempt_id = :attemptId
               AND capability_type = :capabilityType
             LIMIT 1
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("namespaceId", identity.namespaceId)
                .addValue("workflowId", identity.workflowId)
                .addValue("stepId", identity.stepId)
                .addValue("attemptId", identity.attemptId)
                .addValue("capabilityType", CAPABILITY_TYPE),
        ) { rs, _ -> deserialize(rs.getString("payload"), AgentStepResultCapability::class.java) }.firstOrNull()

    private fun findByToken(scope: TenantScope, tokenHash: String): AgentStepResultCapability? {
        // The token digest lives inside the JSONB payload (no dedicated column),
        // so the tenant-scoped scan compares it in constant time, exactly like
        // the Node `#findByToken`.
        val rows = jdbc.query(
            """
            SELECT payload FROM result_capabilities
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND capability_type = :capabilityType
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("capabilityType", CAPABILITY_TYPE),
        ) { rs, _ -> rs.getString("payload") ?: "{}" }.toList()
        return rows.asSequence()
            .filter { payload -> payloadType(payload) == CAPABILITY_ISSUED_TYPE }
            .mapNotNull { payload ->
                runCatching { deserialize(payload, AgentStepResultCapability::class.java) }.getOrNull()
            }
            .firstOrNull { record -> CanonicalJsonHash.safeEqual(record.tokenHash, tokenHash) }
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
        jdbc.query(
            """
            SELECT namespace_id, workflow_id, step_id, attempt_id, result_id, result_status,
                   semantic_signature, payload
              FROM agent_step_results
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId
               AND workflow_id = :workflowId
               AND step_id = :stepId
               AND attempt_id = :attemptId
             ORDER BY created_at ASC
             LIMIT 1
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("namespaceId", namespaceId)
                .addValue("workflowId", workflowId)
                .addValue("stepId", stepId)
                .addValue("attemptId", attemptId),
        ) { rs, _ ->
            AgentStepResultRow(
                namespaceId = rs.getString("namespace_id"),
                workflowId = rs.getString("workflow_id"),
                stepId = rs.getString("step_id"),
                attemptId = rs.getString("attempt_id"),
                resultId = rs.getString("result_id"),
                resultStatus = rs.getString("result_status"),
                semanticSignature = rs.getString("semantic_signature"),
                payload = rs.getString("payload") ?: "{}",
            )
        }.firstOrNull()

    private fun insertReservation(
        scope: TenantScope,
        identity: AgentStepResultCapabilityIdentity,
        resultId: String,
        payload: String,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO agent_step_results (
                organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id,
                result_id, result_status, semantic_signature, payload, created_at
            ) VALUES (
                :organizationId, :workstreamId, :namespaceId, :workflowId, :stepId, :attemptId,
                :resultId, :resultStatus, NULL, CAST(:payload AS jsonb), :createdAt
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("namespaceId", identity.namespaceId)
                .addValue("workflowId", identity.workflowId)
                .addValue("stepId", identity.stepId)
                .addValue("attemptId", identity.attemptId)
                .addValue("resultId", resultId)
                .addValue("resultStatus", COLLISION_DB_STATUS)
                .addValue("payload", payload)
                .addValue("createdAt", Timestamp.from(now)),
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
        jdbc.update(
            """
            INSERT INTO agent_step_results (
                organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id,
                result_id, result_status, semantic_signature, payload, created_at
            ) VALUES (
                :organizationId, :workstreamId, :namespaceId, :workflowId, :stepId, :attemptId,
                :resultId, :resultStatus, :semanticSignature, CAST(:payload AS jsonb), :createdAt
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("namespaceId", capability.namespaceId)
                .addValue("workflowId", capability.workflowId)
                .addValue("stepId", capability.stepId)
                .addValue("attemptId", capability.attemptId)
                .addValue("resultId", resultId)
                .addValue("resultStatus", resultStatus)
                .addValue("semanticSignature", resultHash)
                .addValue("payload", payload)
                .addValue("createdAt", Timestamp.from(now)),
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
        jdbc.update(
            """
            UPDATE agent_step_results
               SET result_status = :resultStatus,
                   semantic_signature = :semanticSignature,
                   payload = CAST(:payload AS jsonb)
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId
               AND workflow_id = :workflowId
               AND step_id = :stepId
               AND attempt_id = :attemptId
               AND result_id = :resultId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("namespaceId", capability.namespaceId)
                .addValue("workflowId", capability.workflowId)
                .addValue("stepId", capability.stepId)
                .addValue("attemptId", capability.attemptId)
                .addValue("resultId", resultId)
                .addValue("resultStatus", resultStatus)
                .addValue("semanticSignature", resultHash)
                .addValue("payload", payload),
        )
    }

    private fun insertCapability(
        scope: TenantScope,
        identity: AgentStepResultCapabilityIdentity,
        resultId: String,
        capability: AgentStepResultCapability,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO result_capabilities (
                organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id,
                result_id, capability_id, capability_type, payload, created_at
            ) VALUES (
                :organizationId, :workstreamId, :namespaceId, :workflowId, :stepId, :attemptId,
                :resultId, :capabilityId, :capabilityType, CAST(:payload AS jsonb), :createdAt
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("workstreamId", scope.workstreamId)
                .addValue("namespaceId", identity.namespaceId)
                .addValue("workflowId", identity.workflowId)
                .addValue("stepId", identity.stepId)
                .addValue("attemptId", identity.attemptId)
                .addValue("resultId", resultId)
                .addValue("capabilityId", capability.capabilityId)
                .addValue("capabilityType", CAPABILITY_TYPE)
                .addValue("payload", serialize(capability))
                .addValue("createdAt", Timestamp.from(now)),
        )
    }

    private fun insertOutbox(scope: TenantScope, submitted: AgentStepResultSubmitted, now: Instant) {
        val payload = objectMapper.createObjectNode().apply {
            put("aggregateType", "agent_step_result")
            put("attemptId", submitted.attemptId)
            put("resultId", submitted.resultId)
            put("status", submitted.status.name)
        }
        jdbc.update(
            """
            INSERT INTO outbox_events (
                organization_id, id, workstream_id, event_type, payload, status, created_at
            ) VALUES (
                :organizationId, :id, :workstreamId, :eventType, CAST(:payload AS jsonb), :status, :createdAt
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId)
                .addValue("id", UUID.randomUUID().toString())
                .addValue("workstreamId", scope.workstreamId)
                .addValue("eventType", "result_submitted")
                .addValue("payload", serialize(payload))
                .addValue("status", "pending")
                .addValue("createdAt", Timestamp.from(now)),
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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

    /** Reservation payload persisted inside `agent_step_results` at issue time. */
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

        val DB_RESULT_STATUS: Map<AgentStepResultStatus, String> = mapOf(
            AgentStepResultStatus.PASS to "success",
            AgentStepResultStatus.FAIL to "failure",
        )
        val ATTEMPT_TERMINAL_STATUS: Map<AgentStepResultStatus, String> = mapOf(
            AgentStepResultStatus.PASS to "completed",
            AgentStepResultStatus.FAIL to "failed",
        )
    }
}
