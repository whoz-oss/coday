package io.whozoss.factory.forge.bmad

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ForgeCandidateSealTest {
    private val commit = "a".repeat(40)
    private val digest = "b".repeat(64)
    private val packet = "c".repeat(64)
    private val code = ForgeCandidateSeal.CodeTree("whoz", "integration", commit,
        clean = true, collisionFree = true, reviewed = true, verified = true)
    private val build = ForgeCandidateSeal.CiBuild("whoz", commit, digest)
    private val deployed = ForgeCandidateSeal.Deployment("integration", "whoz", commit, digest, "deploy-42")

    private fun seal(
        code: ForgeCandidateSeal.CodeTree = this.code,
        build: ForgeCandidateSeal.CiBuild = this.build,
        deployed: ForgeCandidateSeal.Deployment = this.deployed,
        generation: Long = 1,
    ) = ForgeCandidateSeal.seal("delivery-run", generation, packet, code, build, deployed)

    @Test
    fun `one observed revision seals a stable candidate and a later generation has a new identity`() {
        val first = seal() as ForgeCandidateSeal.Result.Sealed
        val replay = seal() as ForgeCandidateSeal.Result.Sealed
        val replacement = seal(generation = 2) as ForgeCandidateSeal.Result.Sealed

        assertThat(first).isEqualTo(replay)
        assertThat(first.candidate.id).isNotEqualTo(replacement.candidate.id)
        assertThat(first.candidate.packetDigest).isEqualTo(packet)
        assertThat(first.candidate.commit).isEqualTo(commit)
        assertThat(first.candidate.buildDigest).isEqualTo(digest)
        assertThat(first.candidate.deploymentReceipt).isEqualTo("deploy-42")
    }

    @Test
    fun `dirty colliding or unverified integration tree cannot seal`() {
        assertThat(seal(code = code.copy(clean = false)))
            .isEqualTo(ForgeCandidateSeal.Result.Refused("UNSAFE_INTEGRATION_TREE"))
        assertThat(seal(code = code.copy(collisionFree = false)))
            .isEqualTo(ForgeCandidateSeal.Result.Refused("UNSAFE_INTEGRATION_TREE"))
        assertThat(seal(code = code.copy(reviewed = false)))
            .isEqualTo(ForgeCandidateSeal.Result.Refused("UNVERIFIED_INTEGRATION_TREE"))
        assertThat(seal(code = code.copy(verified = false)))
            .isEqualTo(ForgeCandidateSeal.Result.Refused("UNVERIFIED_INTEGRATION_TREE"))
    }

    @Test
    fun `CI and observed deployment must attest the same commit and build bytes`() {
        assertThat(seal(build = build.copy(commit = "d".repeat(40))))
            .isEqualTo(ForgeCandidateSeal.Result.Refused("CI_SOURCE_MISMATCH"))
        assertThat(seal(deployed = deployed.copy(commit = "d".repeat(40))))
            .isEqualTo(ForgeCandidateSeal.Result.Refused("DEPLOYMENT_MISMATCH"))
        assertThat(seal(deployed = deployed.copy(artifactDigest = "e".repeat(64))))
            .isEqualTo(ForgeCandidateSeal.Result.Refused("DEPLOYMENT_MISMATCH"))
    }
}
