package io.whozoss.factory.forge.bmad

/** The approved packet is pinned to committed Git bytes, without a Factory blob copy. */
data class ForgePacketIdentity(val repository: String, val commit: String, val manifestSha256: String) {
    init {
        require(repository.isNotBlank() && COMMIT_SHA.matches(commit) && SHA256.matches(manifestSha256))
    }
}

data class ForgeCandidateIdentity(
    val candidateId: String,
    val generation: Long,
    val packet: ForgePacketIdentity,
    val repository: String,
    val integrationBranch: String,
    val integrationCommit: String,
    /** Successful integration deployment offered for validation, not tested-version proof. */
    val deployedTag: String,
    val integrationTarget: String,
    val deploymentReceipt: String,
) {
    init {
        require(candidateId.isNotBlank() && generation > 0 && repository.isNotBlank() && integrationBranch.isNotBlank())
        require(COMMIT_SHA.matches(integrationCommit) && deployedTag.isNotBlank())
        require(integrationTarget.isNotBlank() && deploymentReceipt.isNotBlank())
    }
}

private val SHA256 = Regex("^[a-f0-9]{64}$")
private val COMMIT_SHA = Regex("^[a-f0-9]{40}$")

enum class ForgeHumanGate { PACKET, TECHNICAL, FUNCTIONAL }
enum class ForgeHumanOutcome { APPROVE, REJECT }

/** The caller's actor and allowed gate come from server authorization, never the request body. */
data class ForgeAuthorizedHuman(val actorId: String, val role: String, val gates: Set<ForgeHumanGate>) {
    init { require(actorId.isNotBlank() && role.isNotBlank()) }
}

data class ForgeHumanDecision(
    val gate: ForgeHumanGate,
    val outcome: ForgeHumanOutcome,
    val actorId: String,
    val actorRole: String,
    val interactionId: String,
    val packet: ForgePacketIdentity,
    val candidate: ForgeCandidateIdentity? = null,
)

/**
 * Pure Forge decision rules for a future transactional Factory human-reply hook.
 * The host must persist this state with the interaction and transition in one CAS
 * transaction. A later unrelated integration deployment does not invalidate
 * the presented candidate or imply which version the human actually tested.
 */
data class ForgeHumanAuthority(
    val packet: ForgePacketIdentity,
    val packetDecision: ForgeHumanDecision? = null,
    val candidate: ForgeCandidateIdentity? = null,
    val technicalDecision: ForgeHumanDecision? = null,
    val functionalDecision: ForgeHumanDecision? = null,
) {
    fun presentPacket(next: ForgePacketIdentity): ForgeHumanAuthority {
        require(next != packet) { "A new packet generation needs a new artifact identity or digest" }
        return ForgeHumanAuthority(packet = next)
    }

    fun sealCandidate(next: ForgeCandidateIdentity): ForgeHumanAuthority {
        require(canBuild && next.packet == packet) { "Candidate requires the current approved packet" }
        require(candidate == null || next.generation > candidate.generation) { "Candidate generation must increase" }
        return copy(candidate = next, technicalDecision = null, functionalDecision = null)
    }

    fun decide(
        gate: ForgeHumanGate,
        outcome: ForgeHumanOutcome,
        human: ForgeAuthorizedHuman,
        interactionId: String,
        expectedPacket: ForgePacketIdentity,
        expectedCandidate: ForgeCandidateIdentity? = null,
    ): ForgeHumanAuthority {
        require(gate in human.gates) { "Actor is not authorized for this gate" }
        require(interactionId.isNotBlank()) { "A durable interaction is required" }
        require(expectedPacket == packet) { "Packet decision is stale" }
        if (gate == ForgeHumanGate.PACKET) {
            require(expectedCandidate == null && candidate == null && packetDecision == null) { "Packet gate is closed" }
        } else {
            require(canBuild && candidate != null && expectedCandidate == candidate) { "Candidate decision is stale" }
            require(technicalDecision == null || gate != ForgeHumanGate.TECHNICAL) { "Technical gate is closed" }
            require(functionalDecision == null || gate != ForgeHumanGate.FUNCTIONAL) { "Functional gate is closed" }
        }
        val decision = ForgeHumanDecision(gate, outcome, human.actorId, human.role, interactionId, packet, expectedCandidate)
        return when (gate) {
            ForgeHumanGate.PACKET -> copy(packetDecision = decision)
            ForgeHumanGate.TECHNICAL -> copy(technicalDecision = decision)
            ForgeHumanGate.FUNCTIONAL -> copy(functionalDecision = decision)
        }
    }

    val canBuild: Boolean get() = packetDecision?.outcome == ForgeHumanOutcome.APPROVE && packetDecision.packet == packet

    val canComplete: Boolean get() = canBuild && candidate != null &&
        technicalDecision?.outcome == ForgeHumanOutcome.APPROVE &&
        functionalDecision?.outcome == ForgeHumanOutcome.APPROVE &&
        technicalDecision.candidate == candidate && functionalDecision.candidate == candidate &&
        technicalDecision.packet == packet && functionalDecision.packet == packet
}
