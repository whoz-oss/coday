package io.whozoss.factory.agentattempt.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Lot D — JSON serialization/deserialization of the frozen attempt context
 * envelope. The envelope must round-trip losslessly (including nested maps,
 * lists, nulls and the amendment pin) because it is persisted verbatim on the
 * durable attempt and replayed on recoveries.
 */
class AttemptContextEnvelopeTest {

    @Test
    fun `the envelope round-trips through json without loss`() {
        val envelope = AttemptContextEnvelope(
            organizationId = "org-1",
            workstreamId = "ws-1",
            namespaceId = "ns-1",
            workflowId = "wf-1",
            stepId = "step-a",
            attemptNumber = 2,
            agentName = "architect",
            ticket = "ABC-42",
            briefHash = CanonicalJsonHash.sha256("brief body"),
            inputs = mapOf(
                "A" to mapOf("summary" to "done", "claims" to listOf("c1", "c2"), "findings" to emptyList<String>()),
                "B" to "plain",
            ),
            runBrief = mapOf("kind" to "run-brief", "encoding" to "markdown", "content" to "# Obj"),
            controllerRequest = "Implement the thing",
            expectedAmendmentSeq = 7L,
        )

        val restored = AttemptContextEnvelope.fromJson(envelope.toJson())

        assertThat(restored).isEqualTo(envelope)
        assertThat(restored.schemaVersion).isEqualTo(AttemptContextEnvelope.CONTEXT_ENVELOPE_SCHEMA_VERSION)
        assertThat(restored.expectedAmendmentSeq).isEqualTo(7L)
        assertThat(restored.inputs["A"]).isInstanceOf(Map::class.java)
    }

    @Test
    fun `optional fields serialize as explicit nulls and deserialize as null`() {
        val envelope = AttemptContextEnvelope(
            organizationId = "org-1",
            workstreamId = "ws-1",
            namespaceId = "ns-1",
            workflowId = "wf-1",
            stepId = "step-a",
            attemptNumber = 1,
            agentName = "architect",
            briefHash = CanonicalJsonHash.sha256("b"),
        )

        val json = envelope.toJson()
        assertThat(json).contains("\"ticket\":null")
        assertThat(json).contains("\"expectedAmendmentSeq\":null")

        val restored = AttemptContextEnvelope.fromJson(json)
        assertThat(restored.ticket).isNull()
        assertThat(restored.runBrief).isNull()
        assertThat(restored.controllerRequest).isNull()
        assertThat(restored.expectedAmendmentSeq).isNull()
    }

    @Test
    fun `the brief hash pins the exact brief text`() {
        val brief = "## Current step\nId: A"
        val envelope = AttemptContextEnvelope(
            organizationId = "org-1",
            workstreamId = "ws-1",
            namespaceId = "ns-1",
            workflowId = "wf-1",
            stepId = "step-a",
            attemptNumber = 1,
            agentName = "architect",
            briefHash = CanonicalJsonHash.sha256(brief),
        )
        assertThat(envelope.briefHash).isEqualTo(CanonicalJsonHash.sha256(brief))
        assertThat(envelope.briefHash).isNotEqualTo(CanonicalJsonHash.sha256("$brief "))
    }
}
