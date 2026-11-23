package io.whozoss.factory.forge.bmad

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ForgeCandidateSealTest {
    private val workCommit = "a".repeat(40)
    private val integrationCommit = "b".repeat(40)
    private val packet = "c".repeat(64)
    private val code = ForgeCandidateSeal.CodeTree("whoz", "work/talent-portal/ticket", workCommit,
        "integration/talent-portal", integrationCommit, workCommitIncluded = true,
        clean = true, collisionFree = true, reviewed = true, verified = true)
    private val build = ForgeCandidateSeal.CiBuild("whoz", integrationCommit,
        "integration-talent-portal_rc100", integrationCommit, successful = true)
    private val deployed = ForgeCandidateSeal.Deployment("forge-talent-portal",
        "integration-talent-portal_rc100", successful = true, receipt = "deploy-42")

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
        assertThat(first.candidate.workCommit).isEqualTo(workCommit)
        assertThat(first.candidate.integrationCommit).isEqualTo(integrationCommit)
        assertThat(first.candidate.deployedTag).isEqualTo("integration-talent-portal_rc100")
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
    fun `a successful deployment tag must point to an integration commit containing the work commit`() {
        assertThat(seal(code = code.copy(workCommitIncluded = false)))
            .isEqualTo(ForgeCandidateSeal.Result.Refused("WORK_COMMIT_NOT_INTEGRATED"))
        assertThat(seal(build = build.copy(integrationCommit = "d".repeat(40))))
            .isEqualTo(ForgeCandidateSeal.Result.Refused("CI_SOURCE_MISMATCH"))
        assertThat(seal(build = build.copy(tagCommit = "d".repeat(40))))
            .isEqualTo(ForgeCandidateSeal.Result.Refused("CI_SOURCE_MISMATCH"))
        assertThat(seal(build = build.copy(successful = false)))
            .isEqualTo(ForgeCandidateSeal.Result.Refused("CI_SOURCE_MISMATCH"))
        assertThat(seal(deployed = deployed.copy(successful = false)))
            .isEqualTo(ForgeCandidateSeal.Result.Refused("DEPLOYMENT_UNSUCCESSFUL"))
        assertThat(seal(deployed = deployed.copy(tag = "integration-talent-portal_rc101")))
            .isEqualTo(ForgeCandidateSeal.Result.Refused("DEPLOYMENT_MISMATCH"))
    }
}
