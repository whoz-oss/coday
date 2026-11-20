package io.whozoss.factory.workspace

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.sdk.spi.WorkspaceCaptureStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkspaceDeltaCollectorPreparationTest {
    @Test
    fun `strict baseline requires an explicit ready workspace and a complete change inventory`() {
        //given
        val proxy = mockk<AgentOsProxyClient>()
        val ready = mapOf("equipped" to true, "status" to "READY", "path" to "/case/repo")
        val changes = mapOf("base" to "a".repeat(40), "files" to emptyList<Any>())
        every { proxy.fetchWorkspaceChanges("case-1", null) } returnsMany listOf(
            ready,
            (ready - "status") + ("changes" to changes),
            ready + ("changes" to (changes + ("files" to listOf(mapOf("status" to "MODIFIED"))))),
            ready + ("changes" to changes),
        )
        val collector = WorkspaceDeltaCollector(proxy)

        //when
        val snapshots = List(4) {
            collector.captureBeforeTurnWhenReady(
                caseId = "case-1", maxWaitMillis = 0, requireTrustedBaseline = true,
            )
        }

        //then
        assertThat(snapshots.map { it.captureStatus }).containsExactly(
            WorkspaceCaptureStatus.DEGRADED, WorkspaceCaptureStatus.DEGRADED,
            WorkspaceCaptureStatus.DEGRADED, WorkspaceCaptureStatus.COMPLETE,
        )
    }

    @Test
    fun `strict baseline rejects changed files without usable patch evidence`() {
        //given
        val proxy = mockk<AgentOsProxyClient>()
        every { proxy.fetchWorkspaceChanges("case-1", null) } returns mapOf(
            "equipped" to true, "status" to "READY", "path" to "/case/repo",
            "changes" to mapOf("base" to "a".repeat(40), "files" to listOf(
                mapOf("path" to "changed.md", "status" to "MODIFIED"),
            )),
        )
        every { proxy.fetchWorkspaceDiff("case-1", "changed.md", null) } returnsMany listOf(
            null,
            mapOf("patch" to "", "message" to "Binary file or symbolic link — no text preview"),
            mapOf("patch" to "diff --git a/changed.md b/changed.md"),
        )
        val collector = WorkspaceDeltaCollector(proxy)

        //when
        val snapshots = List(3) {
            collector.captureBeforeTurnWhenReady(
                caseId = "case-1", maxWaitMillis = 0, requireTrustedBaseline = true,
            )
        }

        //then
        assertThat(snapshots.map { it.captureStatus }).containsExactly(
            WorkspaceCaptureStatus.DEGRADED, WorkspaceCaptureStatus.DEGRADED, WorkspaceCaptureStatus.COMPLETE,
        )
    }

    @Test
    fun `strict baseline does not infer watched file absence from an unmounted remote path`() {
        //given
        val proxy = mockk<AgentOsProxyClient>()
        every { proxy.fetchWorkspaceChanges("case-1", null) } returns mapOf(
            "equipped" to true, "status" to "READY", "path" to "/case/repo",
            "changes" to mapOf("base" to "a".repeat(40), "files" to emptyList<Any>()),
        )
        val collector = WorkspaceDeltaCollector(proxy)
        val watchedPaths = listOf("SPEC.md")

        //when
        val strict = collector.captureBeforeTurnWhenReady(
            caseId = "case-1", watchedPaths = watchedPaths, maxWaitMillis = 0, requireTrustedBaseline = true,
        )
        val ordinary = collector.captureBeforeTurnWhenReady(
            caseId = "case-1", watchedPaths = watchedPaths, maxWaitMillis = 0,
        )

        //then
        assertThat(strict.captureStatus).isEqualTo(WorkspaceCaptureStatus.DEGRADED)
        assertThat(strict.degradedReason).isEqualTo("WATCHED_FILE_EVIDENCE_UNAVAILABLE:SPEC.md")
        assertThat(ordinary.captureStatus).isEqualTo(WorkspaceCaptureStatus.COMPLETE)
    }

    @Test
    fun `initial snapshot waits for the new case worktree before the turn`() {
        val proxy = mockk<AgentOsProxyClient>()
        every { proxy.fetchWorkspaceChanges("case-1", null) } returnsMany listOf(
            null,
            mapOf("equipped" to false, "status" to "PREPARING"),
            mapOf("equipped" to true, "status" to "READY", "changes" to mapOf("base" to "abc", "files" to emptyList<Any>())),
        )

        val snapshot = WorkspaceDeltaCollector(proxy).captureBeforeTurnWhenReady("case-1", maxWaitMillis = 1_000)

        assertThat(snapshot.captureStatus).isEqualTo(WorkspaceCaptureStatus.COMPLETE)
        assertThat(snapshot.baseSha).isEqualTo("abc")
        verify(exactly = 3) { proxy.fetchWorkspaceChanges("case-1", null) }
    }
}
