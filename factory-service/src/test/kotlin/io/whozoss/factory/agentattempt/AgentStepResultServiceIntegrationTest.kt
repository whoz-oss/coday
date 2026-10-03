package io.whozoss.factory.agentattempt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.AgentStepResultObservedIdentity
import io.whozoss.factory.agentattempt.domain.IdempotencyKeyCollisionException
import io.whozoss.factory.agentattempt.domain.InvalidResultCapabilityIdentityException
import io.whozoss.factory.agentattempt.domain.ResultCapabilityAlreadyIssuedException
import io.whozoss.factory.agentattempt.domain.ResultCapabilityExpiredException
import io.whozoss.factory.agentattempt.domain.ResultCapabilityIdentityConflictException
import io.whozoss.factory.agentattempt.domain.ResultCapabilityInvalidException
import io.whozoss.factory.agentattempt.domain.ResultIdentityMismatchException
import io.whozoss.factory.agentattempt.domain.ResultSchemaInvalidException
import io.whozoss.factory.agentattempt.domain.ResultSemanticCollisionException
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jAgentStepResultRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

/**
 * Embedded-Neo4j integration tests of [AgentStepResultService] (the former V6
 * `agent_step_*` tables and the V4 `outbox_events` / `idempotency_records` tables
 * are now graph nodes).
 *
 * Extends [Neo4jDomainIntegrationTest]: the engine is the in-process Neo4j test
 * harness, so no Docker is required.
 */
class AgentStepResultServiceIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var service: AgentStepResultService

    @Autowired
    private lateinit var attempts: AgentStepAttemptRepository

    @Autowired
    private lateinit var resultNodes: SpringDataNeo4jAgentStepResultRepository

    @Autowired
    private lateinit var outboxNodes: SpringDataNeo4jOutboxRepository

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val namespace = "ns-a7"
    private val workflow = "wf-a7"
    private val step = "step-a7"
    private val caseId = "case-a7"
    private val agentName = "Agent"
    private val briefHash = "sha256:${"a".repeat(64)}"

    @Test
    fun `submission writes the result, terminalizes the attempt and enqueues an outbox event`() {
        seedAttempt("attempt-full")
        val issued = service.issue(scope, identity("attempt-full"))

        val outcome = service.submit(scope, issued.token, business("PASS", "ok"), observed("attempt-full"), null)

        assertThat(outcome.idempotent).isFalse()
        assertThat(outcome.resultId).isNotBlank()
        assertThat(outcome.resultHash).startsWith("sha256:")

        val result = resultNode("attempt-full")
        assertThat(result?.resultStatus).isEqualTo("success")
        assertThat(result?.semanticSignature).isEqualTo(outcome.resultHash)

        val attempt = attempts.find(scope, namespace, workflow, step, "attempt-full")
        assertThat(attempt?.status).isEqualTo("completed")
        assertThat(attempt?.revision).isEqualTo(2)

        val outbox = outboxEvents()
        assertThat(outbox).hasSize(1)
        assertThat(outbox.single().eventType).isEqualTo("result_submitted")
        assertThat(outbox.single().status).isEqualTo("pending")
    }

    @Test
    fun `an identical replay is idempotent and does not write again`() {
        seedAttempt("attempt-idem")
        val issued = service.issue(scope, identity("attempt-idem"))
        val first = service.submit(scope, issued.token, business("PASS", "ok"), observed("attempt-idem"), null)

        val replay = service.submit(scope, issued.token, business("PASS", "ok"), observed("attempt-idem"), null)

        assertThat(replay.idempotent).isTrue()
        assertThat(replay.resultId).isEqualTo(first.resultId)
        assertThat(countResults("attempt-idem")).isEqualTo(1)
        assertThat(countOutbox()).isEqualTo(1)
    }

    @Test
    fun `a divergent result for the same attempt is a semantic collision`() {
        seedAttempt("attempt-collision")
        val issued = service.issue(scope, identity("attempt-collision"))
        service.submit(scope, issued.token, business("PASS", "first"), observed("attempt-collision"), null)

        assertThatThrownBy {
            service.submit(scope, issued.token, business("PASS", "second"), observed("attempt-collision"), null)
        }
            .isInstanceOf(ResultSemanticCollisionException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "RESULT_SEMANTIC_COLLISION")

        assertThat(countResults("attempt-collision")).isEqualTo(1)
        assertThat(countOutbox()).isEqualTo(1)
    }

    @Test
    fun `an expired capability is rejected without writing anything`() {
        seedAttempt("attempt-expired")
        val base = Instant.parse("2026-05-01T00:00:00Z")
        val issued = service.issue(scope, identity("attempt-expired"), now = base, ttlSeconds = 60)
        val now = base.plusSeconds(120)

        assertThatThrownBy {
            service.submit(scope, issued.token, business("PASS", "ok"), observed("attempt-expired"), null, now)
        }
            .isInstanceOf(ResultCapabilityExpiredException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "RESULT_CAPABILITY_EXPIRED")

        assertThat(countOutbox()).isEqualTo(0)
        assertThat(attempts.find(scope, namespace, workflow, step, "attempt-expired")?.status).isEqualTo("running")
        assertThat(resultPayloadType("attempt-expired")).isEqualTo("capability-reserved")
    }

    @Test
    fun `an unknown capability token is rejected`() {
        seedAttempt("attempt-unknown")

        assertThatThrownBy {
            service.submit(scope, "unknown-token-0000000000000000000000000", business("PASS", "ok"), observed("attempt-unknown"), null)
        }
            .isInstanceOf(ResultCapabilityInvalidException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "RESULT_CAPABILITY_INVALID")
        assertThat(countOutbox()).isEqualTo(0)
    }

    @Test
    fun `a mismatched observed identity is rejected`() {
        seedAttempt("attempt-identity")
        val issued = service.issue(scope, identity("attempt-identity"))

        assertThatThrownBy {
            service.submit(
                scope,
                issued.token,
                business("PASS", "ok"),
                AgentStepResultObservedIdentity("attempt-identity", "other-case", agentName),
                null,
            )
        }
            .isInstanceOf(ResultIdentityMismatchException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "RESULT_IDENTITY_MISMATCH")
    }

    @Test
    fun `a mismatched observed namespace is rejected`() {
        seedAttempt("attempt-ns")
        val issued = service.issue(scope, identity("attempt-ns"))

        assertThatThrownBy {
            service.submit(
                scope,
                issued.token,
                business("PASS", "ok"),
                AgentStepResultObservedIdentity("attempt-ns", caseId, agentName, namespaceId = "other-namespace"),
                null,
            )
        }
            .isInstanceOf(ResultIdentityMismatchException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "RESULT_IDENTITY_MISMATCH")

        assertThat(countOutbox()).isEqualTo(0)
        assertThat(attempts.find(scope, namespace, workflow, step, "attempt-ns")?.status).isEqualTo("running")
    }

    @Test
    fun `a matching observed namespace is accepted`() {
        seedAttempt("attempt-ns-ok")
        val issued = service.issue(scope, identity("attempt-ns-ok"))

        val outcome = service.submit(
            scope,
            issued.token,
            business("PASS", "ok"),
            AgentStepResultObservedIdentity("attempt-ns-ok", caseId, agentName, namespaceId = namespace),
            null,
        )

        assertThat(outcome.idempotent).isFalse()
        assertThat(attempts.find(scope, namespace, workflow, step, "attempt-ns-ok")?.status).isEqualTo("completed")
    }

    @Test
    fun `an invalid business schema is rejected before any DB write`() {
        seedAttempt("attempt-schema")
        val issued = service.issue(scope, identity("attempt-schema"))
        val invalid = objectMapper.readTree("""{"status":"PASS","summary":"","claims":{"modifiedFiles":[]}}""")

        assertThatThrownBy {
            service.submit(scope, issued.token, invalid, observed("attempt-schema"), null)
        }
            .isInstanceOf(ResultSchemaInvalidException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "RESULT_SCHEMA_INVALID")
        assertThat(countOutbox()).isEqualTo(0)
    }

    @Test
    fun `issuing a second capability for the same attempt is rejected`() {
        seedAttempt("attempt-cap")

        service.issue(scope, identity("attempt-cap"))

        assertThatThrownBy { service.issue(scope, identity("attempt-cap")) }
            .isInstanceOf(ResultCapabilityAlreadyIssuedException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "RESULT_CAPABILITY_ALREADY_ISSUED")
        assertThatThrownBy { service.issue(scope, identity("attempt-cap").copy(agentName = "Other")) }
            .isInstanceOf(ResultCapabilityIdentityConflictException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "RESULT_CAPABILITY_IDENTITY_CONFLICT")
    }

    @Test
    fun `an invalid capability identity is rejected`() {
        seedAttempt("attempt-bad-cap")

        assertThatThrownBy { service.issue(scope, identity("attempt-bad-cap").copy(briefHash = "sha256:not-hex")) }
            .isInstanceOf(InvalidResultCapabilityIdentityException::class.java)
    }

    @Test
    fun `issuing a capability for an unknown attempt is rejected`() {
        assertThatThrownBy { service.issue(scope, identity("attempt-missing")) }
            .isInstanceOf(InvalidResultCapabilityIdentityException::class.java)
    }

    @Test
    fun `an idempotency key caches the response and rejects a divergent replay`() {
        seedAttempt("attempt-key")
        val issued = service.issue(scope, identity("attempt-key"))
        val first = service.submit(scope, issued.token, business("PASS", "ok"), observed("attempt-key"), "key-1")

        val replay = service.submit(scope, issued.token, business("PASS", "ok"), observed("attempt-key"), "key-1")
        assertThat(replay.idempotent).isTrue()
        assertThat(replay.resultId).isEqualTo(first.resultId)

        assertThatThrownBy {
            service.submit(scope, issued.token, business("PASS", "divergent"), observed("attempt-key"), "key-1")
        }
            .isInstanceOf(IdempotencyKeyCollisionException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "IDEMPOTENCY_KEY_COLLISION")
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun seedAttempt(attemptId: String) {
        attempts.insert(
            scope,
            AgentStepAttemptRecord(
                namespaceId = namespace,
                workflowId = workflow,
                stepId = step,
                attemptId = attemptId,
                agentId = "agent-1",
                status = "running",
                revision = 1,
                payload = "{}",
            ),
        )
    }

    private fun identity(attemptId: String): AgentStepResultCapabilityIdentity =
        AgentStepResultCapabilityIdentity(
            attemptId = attemptId,
            workflowId = workflow,
            stepId = step,
            namespaceId = namespace,
            caseId = caseId,
            agentName = agentName,
            briefHash = briefHash,
        )

    private fun observed(attemptId: String): AgentStepResultObservedIdentity =
        AgentStepResultObservedIdentity(attemptId = attemptId, caseId = caseId, agentName = agentName)

    private fun business(status: String, summary: String): JsonNode =
        objectMapper.readTree(
            """{"status":"$status","summary":"$summary","claims":{"modifiedFiles":[]}}""",
        )

    private fun resultNode(attemptId: String) =
        resultNodes.findFirstByAttempt(ORGANIZATION_ID, WORKSTREAM_ID, namespace, workflow, step, attemptId)

    private fun resultPayloadType(attemptId: String): String? =
        resultNode(attemptId)?.let { objectMapper.readTree(it.payload).path("type").asText() }

    private fun countResults(attemptId: String): Long =
        resultNodes.countByAttempt(ORGANIZATION_ID, WORKSTREAM_ID, namespace, workflow, step, attemptId)

    private fun outboxEvents() = outboxNodes.findAllByOrganization(ORGANIZATION_ID)

    private fun countOutbox(): Int = outboxEvents().size
}
