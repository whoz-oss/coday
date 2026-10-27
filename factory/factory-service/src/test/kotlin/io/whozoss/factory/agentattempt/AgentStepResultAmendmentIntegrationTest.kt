package io.whozoss.factory.agentattempt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.AgentStepResultObservedIdentity
import io.whozoss.factory.agentattempt.domain.ResultSchemaInvalidException
import io.whozoss.factory.agentattempt.domain.ResultSemanticCollisionException
import io.whozoss.factory.agentattempt.domain.StaleAmendmentSequenceException
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jAgentStepResultRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Embedded-Neo4j integration tests of the Lot E step-result extensions: the
 * authoritative amendment counter compare-and-set and the `NEEDS_RESEARCH`
 * verdict persistence (blocked, never sealed as a terminal failure).
 */
class AgentStepResultAmendmentIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var service: AgentStepResultService

    @Autowired
    private lateinit var attempts: AgentStepAttemptRepository

    @Autowired
    private lateinit var results: SpringDataNeo4jAgentStepResultRepository

    @Autowired
    private lateinit var outboxNodes: SpringDataNeo4jOutboxRepository

    @Autowired
    private lateinit var workflowRepository: WorkflowRepository

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val namespace = "ns-e"
    private val workflow = "wf-e"
    private val step = "step-e"
    private val caseId = "case-e"
    private val agentName = "Agent"
    private val briefHash = "sha256:${"a".repeat(64)}"

    // ------------------------------------------------------------------
    // Authoritative amendment counter
    // ------------------------------------------------------------------

    @Test
    fun `the amendment counter increments atomically`() {
        seedInstance(amendmentSeq = 0)

        assertThat(workflowRepository.incrementAmendmentSeq(scope, namespace, workflow)).isEqualTo(1)
        assertThat(workflowRepository.incrementAmendmentSeq(scope, namespace, workflow)).isEqualTo(2)
        assertThat(workflowRepository.findInstance(scope, namespace, workflow)?.amendmentSeq).isEqualTo(2)
    }

    @Test
    fun `a result matching the current amendment sequence is accepted`() {
        seedInstance(amendmentSeq = 0)
        seedAttempt("attempt-cas-ok")
        val issued = service.issue(scope, identity("attempt-cas-ok"))

        val outcome = service.submit(
            scope,
            issued.token,
            business("PASS", "ok", expectedAmendmentSeq = 0),
            observed("attempt-cas-ok"),
            null,
        )

        assertThat(outcome.idempotent).isFalse()
        assertThat(attempts.find(scope, namespace, workflow, step, "attempt-cas-ok")?.status).isEqualTo("completed")
    }

    @Test
    fun `a stale amendment sequence is rejected and writes nothing`() {
        seedInstance(amendmentSeq = 0)
        seedAttempt("attempt-cas-stale")
        val issued = service.issue(scope, identity("attempt-cas-stale"))

        assertThatThrownBy {
            service.submit(
                scope,
                issued.token,
                business("PASS", "ok", expectedAmendmentSeq = 1),
                observed("attempt-cas-stale"),
                null,
            )
        }
            .isInstanceOf(StaleAmendmentSequenceException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "STALE_AMENDMENT_SEQUENCE")

        assertThat(resultPayloadType("attempt-cas-stale")).isEqualTo("capability-reserved")
        assertThat(attempts.find(scope, namespace, workflow, step, "attempt-cas-stale")?.status).isEqualTo("running")
    }

    @Test
    fun `an increment makes a previously valid sequence stale`() {
        seedInstance(amendmentSeq = 0)
        seedAttempt("attempt-cas-bump")
        val issued = service.issue(scope, identity("attempt-cas-bump"))
        workflowRepository.incrementAmendmentSeq(scope, namespace, workflow)

        assertThatThrownBy {
            service.submit(
                scope,
                issued.token,
                business("PASS", "ok", expectedAmendmentSeq = 0),
                observed("attempt-cas-bump"),
                null,
            )
        }.isInstanceOf(StaleAmendmentSequenceException::class.java)
    }

    // ------------------------------------------------------------------
    // NEEDS_RESEARCH verdict
    // ------------------------------------------------------------------

    @Test
    fun `a NEEDS_RESEARCH result is persisted blocked, preserving proof, and never sealed as failed`() {
        seedAttempt("attempt-nr")
        val issued = service.issue(scope, identity("attempt-nr"))
        val needsResearch = objectMapper.readTree(
            """
            {
              "status": "NEEDS_RESEARCH",
              "summary": "missing upstream contract",
              "claims": { "modifiedFiles": ["libs/a.ts"] },
              "artifacts": [ { "kind": "report", "encoding": "markdown", "content": "# blocked" } ],
              "findings": [ { "severity": "blocking", "code": "MISSING", "summary": "need research" } ],
              "expected_amendment_seq": 0
            }
            """.trimIndent(),
        )

        val outcome = service.submit(scope, issued.token, needsResearch, observed("attempt-nr"), null)

        assertThat(outcome.idempotent).isFalse()
        assertThat(resultPayloadType("attempt-nr")).isEqualTo("result-submitted")
        assertThat(resultNode("attempt-nr")?.resultStatus).isEqualTo("needs_research")
        // The proof is durably preserved inside the submitted payload.
        val stored = objectMapper.readTree(resultNode("attempt-nr")!!.payload)
        assertThat(stored.path("status").asText()).isEqualTo("NEEDS_RESEARCH")
        assertThat(stored.path("findings")).hasSize(1)
        assertThat(stored.path("artifacts")).hasSize(1)
        // The result-channel attempt is NOT terminal (blocked, pending research).
        assertThat(attempts.find(scope, namespace, workflow, step, "attempt-nr")?.status).isEqualTo("running")
        assertThat(outboxStatuses()).containsExactly("NEEDS_RESEARCH")
    }

    @Test
    fun `an identical NEEDS_RESEARCH replay is idempotent and a divergent one collides`() {
        seedAttempt("attempt-nr-2")
        val issued = service.issue(scope, identity("attempt-nr-2"))
        val first = business("NEEDS_RESEARCH", "missing", expectedAmendmentSeq = null)

        val created = service.submit(scope, issued.token, first, observed("attempt-nr-2"), null)
        val replay = service.submit(scope, issued.token, first, observed("attempt-nr-2"), null)

        assertThat(created.idempotent).isFalse()
        assertThat(replay.idempotent).isTrue()
        assertThat(replay.resultId).isEqualTo(created.resultId)

        assertThatThrownBy {
            service.submit(scope, issued.token, business("NEEDS_RESEARCH", "different"), observed("attempt-nr-2"), null)
        }.isInstanceOf(ResultSemanticCollisionException::class.java)
    }

    @Test
    fun `reconciliation reports but does not terminalize a NEEDS_RESEARCH result`() {
        seedAttempt("attempt-nr-recover")
        val issued = service.issue(scope, identity("attempt-nr-recover"))
        service.submit(scope, issued.token, business("NEEDS_RESEARCH", "missing"), observed("attempt-nr-recover"), null)

        val report = service.reconcileOnStartup()

        assertThat(report.needsResearchDeferred).isEqualTo(1)
        assertThat(attempts.find(scope, namespace, workflow, step, "attempt-nr-recover")?.status).isEqualTo("running")
    }

    @Test
    fun `a corrigible schema rejection does not consume the capability`() {
        seedAttempt("attempt-fix")
        val issued = service.issue(scope, identity("attempt-fix"))
        val invalid = objectMapper.readTree("""{"status":"PASS","summary":"","claims":{"modifiedFiles":[]}}""")

        assertThatThrownBy {
            service.submit(scope, issued.token, invalid, observed("attempt-fix"), null)
        }.isInstanceOf(ResultSchemaInvalidException::class.java)

        val corrected = service.submit(scope, issued.token, business("PASS", "ok"), observed("attempt-fix"), null)
        assertThat(corrected.idempotent).isFalse()
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun seedInstance(amendmentSeq: Long) {
        workflowRepository.insertInstance(
            scope,
            WorkflowInstanceRecord(
                namespaceId = namespace,
                workflowId = workflow,
                revision = 1,
                status = "active",
                creationCommandHash = "cmd-$workflow",
                instance = mapOf("workflowId" to workflow),
                projection = emptyMap(),
                amendmentSeq = amendmentSeq,
            ),
        )
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

    private fun business(status: String, summary: String, expectedAmendmentSeq: Long? = null): JsonNode =
        objectMapper.valueToTree<JsonNode>(
            buildMap {
                put("status", status)
                put("summary", summary)
                put("claims", mapOf("modifiedFiles" to emptyList<String>()))
                if (expectedAmendmentSeq != null) put("expected_amendment_seq", expectedAmendmentSeq)
            },
        )

    private fun resultNode(attemptId: String) =
        results.findFirstByAttempt(ORGANIZATION_ID, WORKSTREAM_ID, namespace, workflow, step, attemptId)

    private fun resultPayloadType(attemptId: String): String? =
        resultNode(attemptId)?.let { objectMapper.readTree(it.payload).path("type").asText() }

    private fun outboxStatuses(): List<String> =
        outboxNodes.findAllByOrganization(ORGANIZATION_ID).map { objectMapper.readTree(it.payload).path("status").asText() }
}
