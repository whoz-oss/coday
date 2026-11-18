package io.whozoss.factory.forge.bmad

import io.whozoss.factory.sdk.spi.RunView
import io.whozoss.factory.sdk.spi.StepEvidenceObservation
import io.whozoss.factory.sdk.spi.StepEvidenceObservationContext
import io.whozoss.factory.sdk.spi.TrustedGitObjectReader
import io.whozoss.factory.sdk.spi.TrustedPullRequestReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ForgePullRequestEvidenceObserverTest {
    private val commit = "a".repeat(40)
    private val observer = ForgePullRequestEvidenceObserver()
    private val git = object : TrustedGitObjectReader {
        override fun sourceRepository() = "whoz"
        override fun commitExists(commit: String) = true
        override fun readBlob(commit: String, path: String, maxBytes: Int): ByteArray? = null
    }
    private fun observe(receipt: Map<String, Any?>?) = observer.observe(StepEvidenceObservationContext(
        run = RunView("run", "forge.delivery", domainState = mapOf(
            "build_report" to mapOf("work_commit" to commit),
        )),
        flowType = "forge.delivery", stepId = "implementation-review", agentName = "Reviewer",
        attemptId = "attempt", worktreePath = "/unused", report = emptyMap(), gitObjects = git,
        pullRequests = object : TrustedPullRequestReader {
            override fun exactHead(commit: String): Map<String, Any?>? = receipt
        },
    ))

    @Test
    fun `exact open PR head is accepted for independent review`() {
        val evidence = observe(mapOf("repository" to "biznet-io/whoz", "branch" to "work/talent-portal/ticket",
            "headCommit" to commit, "number" to 8488, "url" to "https://github.com/biznet-io/whoz/pull/8488"))
            as StepEvidenceObservation.Observed
        assertThat(evidence.facts["prNumber"]).isEqualTo(8488)
        assertThat(evidence.facts["prHeadCommit"]).isEqualTo(commit)
    }

    @Test
    fun `missing or changed PR head keeps review evidence closed`() {
        assertThat(observe(null)).isInstanceOf(StepEvidenceObservation.Unavailable::class.java)
        assertThat(observe(mapOf("repository" to "biznet-io/whoz", "branch" to "work/talent-portal/ticket",
            "headCommit" to "b".repeat(40), "number" to 8488,
            "url" to "https://github.com/biznet-io/whoz/pull/8488")))
            .isInstanceOf(StepEvidenceObservation.Unavailable::class.java)
    }
}
