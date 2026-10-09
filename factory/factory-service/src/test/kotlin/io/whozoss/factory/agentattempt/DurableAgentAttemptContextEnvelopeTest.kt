package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AttemptContextEnvelope
import io.whozoss.factory.agentattempt.domain.CanonicalJsonHash
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.toDto
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Lot D attestation: the frozen context envelope and the `expected_amendment_seq`
 * pin are persisted on the durable attempt, survive the whole lifecycle and
 * reach the bounded public read model unchanged — the replay guarantee.
 */
class DurableAgentAttemptContextEnvelopeTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var service: DurableAgentAttemptService

    private fun envelope(workflowId: String): AttemptContextEnvelope = AttemptContextEnvelope(
        organizationId = scope.organizationId,
        workstreamId = scope.workstreamId,
        namespaceId = NAMESPACE_ID,
        workflowId = workflowId,
        stepId = STEP_ID,
        attemptNumber = 1,
        agentName = "builder",
        ticket = "ABC-7",
        briefHash = CanonicalJsonHash.sha256("frozen brief"),
        inputs = mapOf("dep" to mapOf("summary" to "ok")),
        expectedAmendmentSeq = 12L,
    )

    @Test
    fun `the frozen context envelope and amendment pin are persisted verbatim and exposed on the dto`() {
        val json = envelope(WORKFLOW_ID).toJson()
        service.register(
            scope,
            DurableAgentAttempt(
                attemptId = "attempt-envelope",
                caseId = "case-1",
                namespaceId = NAMESPACE_ID,
                workflowId = WORKFLOW_ID,
                stepId = STEP_ID,
                attemptNumber = 1,
                agentName = "builder",
                brief = "frozen brief",
                contextEnvelope = json,
                expectedAmendmentSeq = 12L,
            ),
        )

        val persisted = service.find(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-envelope")!!
        assertThat(persisted.brief).isEqualTo("frozen brief")
        assertThat(persisted.contextEnvelope).isEqualTo(json)
        assertThat(persisted.expectedAmendmentSeq).isEqualTo(12L)
        // The persisted envelope itself deserializes back to the original structure.
        assertThat(AttemptContextEnvelope.fromJson(persisted.contextEnvelope!!))
            .isEqualTo(envelope(WORKFLOW_ID))

        val dto = persisted.toDto()
        assertThat(dto.expectedAmendmentSeq).isEqualTo(12L)
    }

    @Test
    fun `an attempt registered without a context envelope keeps a null pin`() {
        service.register(
            scope,
            DurableAgentAttempt(
                attemptId = "attempt-no-envelope",
                caseId = "case-1",
                namespaceId = NAMESPACE_ID,
                workflowId = WORKFLOW_ID,
                stepId = STEP_ID,
                attemptNumber = 1,
                agentName = "builder",
            ),
        )

        val persisted = service.find(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-no-envelope")!!
        assertThat(persisted.contextEnvelope).isNull()
        assertThat(persisted.expectedAmendmentSeq).isNull()
        assertThat(persisted.toDto().expectedAmendmentSeq).isNull()
    }

    companion object {
        private const val NAMESPACE_ID = "ns-envelope"
        private const val WORKFLOW_ID = "wf-envelope"
        private const val STEP_ID = "step-envelope"
    }
}
