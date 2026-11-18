package io.whozoss.factory.forge.bmad

import io.whozoss.factory.sdk.spi.RunView
import io.whozoss.factory.sdk.spi.StepEvidenceObservation
import io.whozoss.factory.sdk.spi.StepEvidenceObservationContext
import io.whozoss.factory.sdk.spi.TrustedDeploymentEvidenceReader
import io.whozoss.factory.sdk.spi.TrustedGitObjectReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ForgeDeploymentEvidenceObserverTest {
    private val workCommit = "a".repeat(40)
    private val integrationCommit = "b".repeat(40)
    private val observer = ForgeDeploymentEvidenceObserver()
    private val git = object : TrustedGitObjectReader {
        override fun sourceRepository() = "whoz"
        override fun commitExists(commit: String) = true
        override fun readBlob(commit: String, path: String, maxBytes: Int): ByteArray? = null
    }

    private fun context(result: Map<String, Any?>? = receipt(), slug: String = "talent-portal") =
        StepEvidenceObservationContext(
            run = RunView("run-1", "forge.delivery", domainState = mapOf(
                "build_report" to mapOf("work_commit" to workCommit),
            )),
            flowType = "forge.delivery", stepId = "integration-observation",
            agentName = "ForgeDeploymentObserver", attemptId = "attempt-1", worktreePath = "/unused",
            report = emptyMap(), gitObjects = git, workstreamId = slug,
            deploymentEvidence = object : TrustedDeploymentEvidenceReader {
                override fun find(workCommit: String, integrationBranch: String, target: String): Map<String, Any?>? {
                    assertThat(integrationBranch).isEqualTo("integration/talent-portal")
                    assertThat(target).isEqualTo("forge-talent-portal")
                    return result
                }
            },
        )

    private fun receipt() = mapOf<String, Any?>(
        "repository" to "biznet-io/whoz", "workCommit" to workCommit,
        "integrationBranch" to "integration/talent-portal", "integrationCommit" to integrationCommit,
        "tag" to "integration-talent-portal_rc100", "target" to "forge-talent-portal",
        "buildRunId" to 44369638977L, "deployRunId" to 44371473992L,
        "deployJobId" to 135042982985L,
    )

    @Test
    fun `trusted receipt opens the observation with the deployed tag and job`() {
        val result = observer.observe(context()) as StepEvidenceObservation.Observed
        assertThat(result.facts["tag"]).isEqualTo("integration-talent-portal_rc100")
        assertThat(result.facts["receipt"])
            .isEqualTo("github-actions:run/44371473992/job/135042982985")
    }

    @Test
    fun `pending or inconsistent GitHub facts keep the step closed`() {
        assertThat(observer.observe(context(result = null)))
            .isEqualTo(StepEvidenceObservation.Unavailable("DEPLOYMENT_PENDING",
                "No successful deployment contains this work commit yet."))
        assertThat(observer.observe(context(result = receipt() + ("workCommit" to "c".repeat(40)))))
            .isInstanceOf(StepEvidenceObservation.Unavailable::class.java)
        assertThat(observer.observe(context(result = receipt() + ("target" to "forge-other"))))
            .isInstanceOf(StepEvidenceObservation.Unavailable::class.java)
    }
}
