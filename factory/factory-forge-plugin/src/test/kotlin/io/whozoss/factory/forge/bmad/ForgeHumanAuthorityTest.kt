package io.whozoss.factory.forge.bmad

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ForgeHumanAuthorityTest {
    private val packet = ForgePacketIdentity("artifact-1", "a".repeat(64))
    private val nextPacket = ForgePacketIdentity("artifact-2", "b".repeat(64))
    private val packetReviewer = ForgeAuthorizedHuman("designer", "design-reviewer", setOf(ForgeHumanGate.PACKET))
    private val technicalReviewer = ForgeAuthorizedHuman("tech", "technical-reviewer", setOf(ForgeHumanGate.TECHNICAL))
    private val functionalReviewer = ForgeAuthorizedHuman("product", "product-owner", setOf(ForgeHumanGate.FUNCTIONAL))

    private fun candidate(generation: Long = 1, packet: ForgePacketIdentity = this.packet) = ForgeCandidateIdentity(
        "candidate-$generation", generation, packet, "whoz", "workstream/forge/master",
        "commit-$generation", "build-$generation", "integration", "deploy-$generation",
    )

    private fun approvedPacket() = ForgeHumanAuthority(packet).decide(
        ForgeHumanGate.PACKET, ForgeHumanOutcome.APPROVE, packetReviewer, "packet-interaction", packet,
    )

    @Test
    fun `packet rejection and replacement require fresh approval before Build`() {
        val rejected = ForgeHumanAuthority(packet).decide(
            ForgeHumanGate.PACKET, ForgeHumanOutcome.REJECT, packetReviewer, "reject-interaction", packet,
        )
        assertThat(rejected.canBuild).isFalse()
        assertThatThrownBy { rejected.sealCandidate(candidate()) }.isInstanceOf(IllegalArgumentException::class.java)
        val replacement = rejected.presentPacket(nextPacket)
        assertThat(replacement.packetDecision).isNull()
        assertThatThrownBy {
            replacement.decide(ForgeHumanGate.PACKET, ForgeHumanOutcome.APPROVE, packetReviewer, "late", packet)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(replacement.canBuild).isFalse()
    }

    @Test
    fun `unauthorized and duplicate decisions fail before changing authority`() {
        val state = ForgeHumanAuthority(packet)
        assertThatThrownBy {
            state.decide(ForgeHumanGate.PACKET, ForgeHumanOutcome.APPROVE, technicalReviewer, "wrong-role", packet)
        }.isInstanceOf(IllegalArgumentException::class.java)
        val approved = approvedPacket()
        assertThat(approved.packetDecision?.actorId).isEqualTo("designer")
        assertThat(approved.packetDecision?.actorRole).isEqualTo("design-reviewer")
        assertThat(approved.packetDecision?.interactionId).isEqualTo("packet-interaction")
        assertThatThrownBy {
            approved.decide(ForgeHumanGate.PACKET, ForgeHumanOutcome.APPROVE, packetReviewer, "duplicate", packet)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `both independent decisions must name the current candidate generation`() {
        val first = candidate()
        val state = approvedPacket().sealCandidate(first).decide(
            ForgeHumanGate.TECHNICAL, ForgeHumanOutcome.APPROVE, technicalReviewer, "tech-1", packet, first,
        )
        assertThat(state.canComplete).isFalse()
        assertThatThrownBy {
            state.decide(ForgeHumanGate.FUNCTIONAL, ForgeHumanOutcome.APPROVE, functionalReviewer, "stale", packet, candidate(2))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(state.decide(
            ForgeHumanGate.FUNCTIONAL, ForgeHumanOutcome.APPROVE, functionalReviewer, "functional-1", packet, first,
        ).canComplete).isTrue()
        assertThat(state.decide(
            ForgeHumanGate.FUNCTIONAL, ForgeHumanOutcome.REJECT, functionalReviewer, "functional-reject", packet, first,
        ).canComplete).isFalse()
    }

    @Test
    fun `candidate replacement invalidates both decisions and late replies`() {
        val first = candidate()
        val complete = approvedPacket().sealCandidate(first)
            .decide(ForgeHumanGate.TECHNICAL, ForgeHumanOutcome.APPROVE, technicalReviewer, "tech-1", packet, first)
            .decide(ForgeHumanGate.FUNCTIONAL, ForgeHumanOutcome.APPROVE, functionalReviewer, "functional-1", packet, first)
        val replacement = complete.sealCandidate(candidate(2))
        assertThat(replacement.canComplete).isFalse()
        assertThat(replacement.technicalDecision).isNull()
        assertThat(replacement.functionalDecision).isNull()
        assertThatThrownBy {
            replacement.decide(ForgeHumanGate.TECHNICAL, ForgeHumanOutcome.APPROVE, technicalReviewer, "late", packet, first)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { complete.sealCandidate(first) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a candidate cannot inherit approval from another packet`() {
        assertThatThrownBy { approvedPacket().sealCandidate(candidate(packet = nextPacket)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ForgePacketIdentity("artifact-3", "not-a-digest") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
