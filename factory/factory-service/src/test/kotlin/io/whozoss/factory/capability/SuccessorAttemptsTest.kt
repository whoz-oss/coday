package io.whozoss.factory.capability

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.AttemptContextEnvelope
import io.whozoss.factory.agentattempt.domain.CanonicalJsonHash
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.IssuedCapability
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.persistence.TenantScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Exact behaviour of [SuccessorAttempts], extracted from `NeedsResearchRouter` (pure move). These expectations are
 * written independently of the implementation so that a drift of the NEEDS_RESEARCH briefs, envelopes or capabilities
 * is caught here, besides the router tests that use the same code.
 */
class SuccessorAttemptsTest {

    private val scope = TenantScope("org", "ws")
    private val predecessorId = "6f1c2a9e-0b7d-4c55-9a31-1d2e3f4a5b6c"
    private val successorId = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val frozenBrief =
        "Use Factory attemptId '$predecessorId' and Idempotency-Key 'factory:$predecessorId:result'. Do the work."
    private val envelope = AttemptContextEnvelope(
        organizationId = "org", workstreamId = "ws", namespaceId = "ns", workflowId = "wf", stepId = "work",
        attemptNumber = 1, agentName = "Dev", briefHash = "sha256:old", inputs = mapOf("k" to "v"),
    )

    private fun predecessor(brief: String? = frozenBrief, envelopeJson: String? = envelope.toJson()) = DurableAgentAttempt(
        attemptId = predecessorId, caseId = "case-1", namespaceId = "ns", workflowId = "wf", stepId = "work",
        attemptNumber = 1, agentName = "Dev", brief = brief, contextEnvelope = envelopeJson,
    )

    // ---------------------------------------------------------------- brief

    @Test
    fun `the brief addresses the successor identifiers everywhere and nothing of the predecessor`() {
        val brief = SuccessorAttempts.brief(predecessor(), successorId, supplement = null)

        assertThat(brief).isEqualTo(
            "Use Factory attemptId '$successorId' and Idempotency-Key 'factory:$successorId:result'. Do the work.",
        )
        assertThat(brief).doesNotContain(predecessorId)
    }

    @Test
    fun `a supplement is appended after one blank line`() {
        val brief = SuccessorAttempts.brief(predecessor(), successorId, supplement = "## Extra\nline")

        assertThat(brief).isEqualTo(
            "Use Factory attemptId '$successorId' and Idempotency-Key 'factory:$successorId:result'. Do the work." +
                "\n\n## Extra\nline",
        )
    }

    @Test
    fun `without a frozen brief the supplement alone is returned, or nothing`() {
        assertThat(SuccessorAttempts.brief(predecessor(brief = null), successorId, "only the supplement"))
            .isEqualTo("only the supplement")
        assertThat(SuccessorAttempts.brief(predecessor(brief = null), successorId, null)).isNull()
    }

    @Test
    fun `a blank predecessor id never splices the successor id between the characters`() {
        val blank = predecessor(brief = "abc").copy(attemptId = "")

        assertThat(SuccessorAttempts.brief(blank, successorId, null)).isEqualTo("abc")
    }

    // ------------------------------------------------------------- envelope

    @Test
    fun `the envelope is kept verbatim when the brief did not change or is absent`() {
        val source = predecessor()

        assertThat(SuccessorAttempts.envelope(source, frozenBrief, 2)).isEqualTo(source.contextEnvelope)
        assertThat(SuccessorAttempts.envelope(source, null, 2)).isEqualTo(source.contextEnvelope)
    }

    @Test
    fun `a changed brief re-pins only the brief hash and the attempt number`() {
        val newBrief = "another brief"

        val repinned = AttemptContextEnvelope.fromJson(SuccessorAttempts.envelope(predecessor(), newBrief, 3)!!)

        assertThat(repinned).isEqualTo(envelope.copy(briefHash = CanonicalJsonHash.sha256(newBrief), attemptNumber = 3))
    }

    @Test
    fun `a legacy attempt without envelope stays without one and an unreadable envelope is never guessed`() {
        assertThat(SuccessorAttempts.envelope(predecessor(envelopeJson = null), "new", 2)).isNull()
        assertThat(SuccessorAttempts.envelope(predecessor(envelopeJson = "not-json"), "new", 2)).isEqualTo("not-json")
        assertThat(SuccessorAttempts.envelope(predecessor(envelopeJson = ""), "new", 2)).isEqualTo("")
    }

    // ----------------------------------------------------------- capability

    private val resultService = mockk<AgentStepResultService>()
    private val stepAttempts = mockk<AgentStepAttemptRepository>()

    @Test
    fun `no capability is issued without a result service or without a predecessor`() {
        assertThat(SuccessorAttempts.issueCapability(null, stepAttempts, scope, predecessor(), successorId, "b", "ns", "wf", "work")).isNull()
        assertThat(SuccessorAttempts.issueCapability(resultService, stepAttempts, scope, null, successorId, "b", "ns", "wf", "work")).isNull()
        verify(exactly = 0) { resultService.issue(any(), any(), any(), any()) }
    }

    @Test
    fun `the capability is the successor's own, bound to the hash of its brief, after its result row exists`() {
        val identity = slot<AgentStepResultCapabilityIdentity>()
        val inserted = slot<AgentStepAttemptRecord>()
        every { stepAttempts.exists(scope, "ns", "wf", "work", successorId) } returns false
        every { stepAttempts.insert(scope, capture(inserted)) } just Runs
        every { resultService.issue(scope, capture(identity), any(), any()) } returns IssuedCapability("new-token", "2030-01-01T00:00:00Z")

        val token = SuccessorAttempts.issueCapability(resultService, stepAttempts, scope, predecessor(), successorId, "the brief", "ns", "wf", "work")

        assertThat(token).isEqualTo("new-token")
        assertThat(inserted.captured).isEqualTo(AgentStepAttemptRecord("ns", "wf", "work", successorId, "Dev", "running", 1, "{}"))
        assertThat(identity.captured).isEqualTo(
            AgentStepResultCapabilityIdentity(
                attemptId = successorId, workflowId = "wf", stepId = "work", namespaceId = "ns", caseId = "case-1",
                agentName = "Dev", briefHash = CanonicalJsonHash.sha256("the brief"),
            ),
        )
    }

    @Test
    fun `an existing result row is not registered twice and a missing brief hashes the empty text`() {
        val identity = slot<AgentStepResultCapabilityIdentity>()
        every { stepAttempts.exists(scope, "ns", "wf", "work", successorId) } returns true
        every { resultService.issue(scope, capture(identity), any(), any()) } returns IssuedCapability("t", "2030-01-01T00:00:00Z")

        SuccessorAttempts.issueCapability(resultService, stepAttempts, scope, predecessor(), successorId, null, "ns", "wf", "work")

        verify(exactly = 0) { stepAttempts.insert(any(), any()) }
        assertThat(identity.captured.briefHash).isEqualTo(CanonicalJsonHash.sha256(""))
    }
}
