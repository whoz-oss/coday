package io.whozoss.factory.agentattempt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.AgentStepResultObservedIdentity
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jAgentStepAttemptRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jAgentStepResultRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

/**
 * Integration tests of the result-channel startup reconciliation
 * ([AgentStepResultService.reconcileOnStartup], driven in production by
 * [io.whozoss.factory.agentattempt.service.ResultChannelRecoveryWorker]).
 *
 * The reconciliation closes the crash window of the result channel without
 * ever fabricating an outcome: a submitted result terminalizes its attempt
 * coherently, an expired reservation is observed but left to the
 * durable-attempt recovery, and the pass is idempotent.
 */
class ResultChannelRecoveryTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var service: AgentStepResultService

    @Autowired
    private lateinit var attempts: AgentStepAttemptRepository

    @Autowired
    private lateinit var attemptNodes: SpringDataNeo4jAgentStepAttemptRepository

    @Autowired
    private lateinit var resultNodes: SpringDataNeo4jAgentStepResultRepository

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val namespace = "ns-rec"
    private val workflow = "wf-rec"
    private val step = "step-rec"
    private val caseId = "case-rec"
    private val agentName = "Agent"
    private val briefHash = "sha256:${"d".repeat(64)}"
    private val base = Instant.parse("2026-07-01T00:00:00Z")

    @Test
    fun `a submitted result whose attempt was left non-terminal terminalizes it coherently`() {
        // PASS submitted, then the attempt is flipped back to running (crash window).
        submitAndFlipBackToRunning("attempt-rec-pass", "PASS")
        // FAIL submitted, same simulated crash window.
        submitAndFlipBackToRunning("attempt-rec-fail", "FAIL")

        val report = service.reconcileOnStartup()

        assertThat(report.submittedFinalized).isEqualTo(2)
        assertThat(report.expiredReservations).isEqualTo(0)
        assertThat(attempts.find(scope, namespace, workflow, step, "attempt-rec-pass")?.status).isEqualTo("completed")
        assertThat(attempts.find(scope, namespace, workflow, step, "attempt-rec-fail")?.status).isEqualTo("failed")
    }

    @Test
    fun `an expired unredeemed capability is observed but never turned into an outcome`() {
        seedAttempt("attempt-rec-expired")
        service.issue(scope, identity("attempt-rec-expired"), now = base, ttlSeconds = 60)

        val report = service.reconcileOnStartup(now = base.plusSeconds(120))

        assertThat(report.submittedFinalized).isEqualTo(0)
        assertThat(report.expiredReservations).isEqualTo(1)
        // no outcome was invented: the attempt stays non-terminal and the
        // reservation row is untouched (no data is deleted either)
        assertThat(attempts.find(scope, namespace, workflow, step, "attempt-rec-expired")?.status).isEqualTo("running")
        assertThat(resultPayloadType("attempt-rec-expired")).isEqualTo("capability-reserved")
    }

    @Test
    fun `a non-expired unredeemed capability is left alone`() {
        seedAttempt("attempt-rec-live")
        service.issue(scope, identity("attempt-rec-live"), now = base, ttlSeconds = 600)

        val report = service.reconcileOnStartup(now = base.plusSeconds(120))

        assertThat(report.submittedFinalized).isEqualTo(0)
        assertThat(report.expiredReservations).isEqualTo(0)
        assertThat(attempts.find(scope, namespace, workflow, step, "attempt-rec-live")?.status).isEqualTo("running")
    }

    @Test
    fun `the reconciliation pass is idempotent`() {
        submitAndFlipBackToRunning("attempt-rec-idem", "PASS")

        val first = service.reconcileOnStartup()
        val attemptAfterFirst = attempts.find(scope, namespace, workflow, step, "attempt-rec-idem")

        val second = service.reconcileOnStartup()

        assertThat(first.submittedFinalized).isEqualTo(1)
        assertThat(second.submittedFinalized).isEqualTo(0)
        assertThat(second.expiredReservations).isEqualTo(0)
        // a terminal attempt is immutable: neither status nor revision moved
        val attemptAfterSecond = attempts.find(scope, namespace, workflow, step, "attempt-rec-idem")
        assertThat(attemptAfterSecond?.status).isEqualTo("completed")
        assertThat(attemptAfterSecond?.revision).isEqualTo(attemptAfterFirst?.revision)
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Submits a result, then flips the attempt back to `running` to simulate the crash window. */
    private fun submitAndFlipBackToRunning(attemptId: String, status: String) {
        seedAttempt(attemptId)
        val issued = service.issue(scope, identity(attemptId), now = base)
        service.submit(scope, issued.token, business(status, "rec-$status"), observed(attemptId), null, now = base.plusSeconds(5))
        val node = attemptNodes.findById(
            io.whozoss.factory.agentattempt.persistence.AgentStepAttemptNode.compositeId(
                scope.organizationId,
                scope.workstreamId,
                namespace,
                workflow,
                step,
                attemptId,
            ),
        ).orElseThrow()
        attemptNodes.save(node.copy(status = "running"))
    }

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
        resultNodes
            .findFirstByAttempt(ORGANIZATION_ID, WORKSTREAM_ID, namespace, workflow, step, attemptId)
            ?.let { objectMapper.readTree(it.payload).path("type").asText() }
}
