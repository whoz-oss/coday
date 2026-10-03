package io.whozoss.factory.agentattempt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.AgentStepResultObservedIdentity
import io.whozoss.factory.agentattempt.domain.ResultCapabilityExpiredException
import io.whozoss.factory.agentattempt.domain.ResultIdentityMismatchException
import io.whozoss.factory.agentattempt.domain.ResultSemanticCollisionException
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jAgentStepResultRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

/**
 * Phase 10 attestation: the result-path attempt is SEALED on terminalization
 * and a late result NEVER changes an already sealed verdict.
 *
 * The storage primitive itself is fenced —
 * `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/SpringDataNeo4jAgentStepAttemptRepository.kt`
 * `terminalize` carries `WHERE NOT a.status IN ['completed','failed']`, so an
 * already-sealed attempt matches nothing — and the submission transaction of
 * `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/Neo4jAgentStepResultRepository.kt`
 * treats a 0 update count as "late result ignored for a sealed attempt": it
 * logs, never throws and never rewrites the sealed status. The result row
 * keeps its own late-submission policy (identical → replayed, divergent →
 * semantic collision), so a late capability redemption can never flip a
 * sealed verdict.
 */
class AgentStepResultSealingTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var service: AgentStepResultService

    @Autowired
    private lateinit var attempts: AgentStepAttemptRepository

    @Autowired
    private lateinit var resultNodes: SpringDataNeo4jAgentStepResultRepository

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val namespace = "ns-seal"
    private val workflow = "wf-seal"
    private val step = "step-seal"
    private val caseId = "case-seal"
    private val agentName = "Agent"
    private val briefHash = "sha256:${"b".repeat(64)}"

    @Test
    fun `an identical late replay is idempotent and leaves the sealed attempt untouched`() {
        seedAttempt("attempt-replay")
        val issued = service.issue(scope, identity("attempt-replay"))
        val first = service.submit(scope, issued.token, business("PASS", "ok"), observed("attempt-replay"), null)
        val sealedBefore = attempts.find(scope, namespace, workflow, step, "attempt-replay")
        assertThat(sealedBefore?.status).isEqualTo("completed")

        val replay = service.submit(scope, issued.token, business("PASS", "ok"), observed("attempt-replay"), null)

        assertThat(replay.idempotent).isTrue()
        assertThat(replay.resultId).isEqualTo(first.resultId)
        val sealedAfter = attempts.find(scope, namespace, workflow, step, "attempt-replay")
        assertThat(sealedAfter?.status).isEqualTo("completed")
        assertThat(sealedAfter?.revision).isEqualTo(sealedBefore?.revision)
    }

    @Test
    fun `a divergent late result is a semantic collision and never flips the sealed verdict`() {
        seedAttempt("attempt-collision")
        val issued = service.issue(scope, identity("attempt-collision"))
        service.submit(scope, issued.token, business("PASS", "first"), observed("attempt-collision"), null)

        assertThatThrownBy {
            service.submit(scope, issued.token, business("FAIL", "late-divergent"), observed("attempt-collision"), null)
        }
            .isInstanceOf(ResultSemanticCollisionException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "RESULT_SEMANTIC_COLLISION")

        // The sealed verdict is still the first submission's, not the late one.
        val sealed = attempts.find(scope, namespace, workflow, step, "attempt-collision")
        assertThat(sealed?.status).isEqualTo("completed")
        assertThat(sealed?.revision).isEqualTo(2)
    }

    @Test
    fun `the terminalize primitive itself refuses to rewrite a sealed attempt`() {
        seedAttempt("attempt-fenced")
        val issued = service.issue(scope, identity("attempt-fenced"))
        service.submit(scope, issued.token, business("PASS", "ok"), observed("attempt-fenced"), null)
        val sealed = attempts.find(scope, namespace, workflow, step, "attempt-fenced")
        assertThat(sealed?.status).isEqualTo("completed")

        // Directly invoking the storage primitive against the sealed attempt
        // matches nothing: 0 updated, status and revision untouched.
        val updated = attempts.terminalize(scope, namespace, workflow, step, "attempt-fenced", "failed")

        assertThat(updated).isEqualTo(0)
        val after = attempts.find(scope, namespace, workflow, step, "attempt-fenced")
        assertThat(after?.status).isEqualTo("completed")
        assertThat(after?.revision).isEqualTo(sealed?.revision)
    }

    @Test
    fun `a late result submission against an already sealed attempt is accepted but never changes the sealed verdict`() {
        seedAttempt("attempt-late")
        val issued = service.issue(scope, identity("attempt-late"))
        // The attempt is sealed by another writer (e.g. the startup
        // reconciliation) before the capability is redeemed.
        val terminalized = attempts.terminalize(scope, namespace, workflow, step, "attempt-late", "completed")
        assertThat(terminalized).isEqualTo(1)
        val sealedBefore = attempts.find(scope, namespace, workflow, step, "attempt-late")

        // The late redemption is recorded (result row + outbox) but the fenced
        // terminalize is a no-op: the sealed `completed` verdict survives a
        // FAIL business result.
        val outcome = service.submit(scope, issued.token, business("FAIL", "late"), observed("attempt-late"), null)

        assertThat(outcome.idempotent).isFalse()
        assertThat(resultPayloadType("attempt-late")).isEqualTo("result-submitted")
        val sealedAfter = attempts.find(scope, namespace, workflow, step, "attempt-late")
        assertThat(sealedAfter?.status).isEqualTo("completed")
        assertThat(sealedAfter?.revision).isEqualTo(sealedBefore?.revision)
    }

    @Test
    fun `an expired capability is rejected and alters no attempt`() {
        seedAttempt("attempt-expired")
        val base = Instant.parse("2026-06-01T00:00:00Z")
        val issued = service.issue(scope, identity("attempt-expired"), now = base, ttlSeconds = 60)

        assertThatThrownBy {
            service.submit(scope, issued.token, business("PASS", "ok"), observed("attempt-expired"), null, base.plusSeconds(120))
        }
            .isInstanceOf(ResultCapabilityExpiredException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "RESULT_CAPABILITY_EXPIRED")

        // The expiry guard fires before any write; on a sealed attempt the
        // replay/collision guards fire even earlier, so an expired late token
        // can never reach the attempt — sealed or not, it is never altered.
        assertThat(attempts.find(scope, namespace, workflow, step, "attempt-expired")?.status).isEqualTo("running")
    }

    @Test
    fun `a late redemption stays fenced to the issued trust-boundary identity`() {
        seedAttempt("attempt-identity")
        val issued = service.issue(scope, identity("attempt-identity"))
        service.submit(scope, issued.token, business("PASS", "ok"), observed("attempt-identity"), null)

        // Trust boundary: the observed identity is never taken from
        // model-authored arguments; a mismatch is rejected even against a
        // sealed attempt, and the sealed verdict is left untouched.
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

        assertThat(attempts.find(scope, namespace, workflow, step, "attempt-identity")?.status).isEqualTo("completed")
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

    private fun resultPayloadType(attemptId: String): String? =
        resultNodes.findFirstByAttempt(ORGANIZATION_ID, WORKSTREAM_ID, namespace, workflow, step, attemptId)
            ?.let { objectMapper.readTree(it.payload).path("type").asText() }
}
