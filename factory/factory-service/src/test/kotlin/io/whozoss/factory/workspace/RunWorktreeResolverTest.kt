package io.whozoss.factory.workspace

import io.whozoss.factory.persistence.TenantScope
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit tests of the explicit run-worktree binding (Lot F).
 *
 * They prove that `code` verifications are rooted on the run's worktree (not the
 * namespace repo root) whenever a workspace exists, and that a non-usable
 * workspace fails instead of silently reusing another directory.
 */
class RunWorktreeResolverTest {

    private val scope = TenantScope("org", "ws")
    private val workflowId = "wf-1"
    private val fallback = Path.of("/repo/namespace-root")
    private val worktree = Path.of("/worktrees/wf-1")

    private class ScriptedStatePort(private val snapshots: List<WorkspaceSnapshot?>) : WorkspaceStatePort {
        private var index = 0
        override fun latestForWorkflow(scope: TenantScope, workflowId: String): WorkspaceSnapshot? {
            val snapshot = snapshots[minOf(index, snapshots.lastIndex)]
            index++
            return snapshot
        }
    }

    private fun resolver(snapshots: List<WorkspaceSnapshot?>): RunWorktreeResolver =
        RunWorktreeResolver(
            WorkspaceStateWaiter(
                ScriptedStatePort(snapshots),
                WorkspaceProperties(enabled = true, readyTimeoutMs = 1_000, readyPollIntervalMs = 1),
            ),
        )

    @Test
    fun `a READY workspace roots code steps on the run worktree`() {
        val resolved = resolver(listOf(WorkspaceSnapshot(WorkspaceStatus.READY, worktreePath = worktree.toString())))
            .resolve(scope, workflowId, fallback)

        assertThat(resolved).isEqualTo(worktree)
    }

    @Test
    fun `a workspace that becomes READY is waited for, then bound`() {
        val resolved = resolver(
            listOf(
                WorkspaceSnapshot(WorkspaceStatus.PREPARING),
                WorkspaceSnapshot(WorkspaceStatus.READY, worktreePath = worktree.toString()),
            ),
        ).resolve(scope, workflowId, fallback)

        assertThat(resolved).isEqualTo(worktree)
    }

    @Test
    fun `with no requested workspace the explicit fallback root is kept`() {
        val resolved = resolver(listOf(null)).resolve(scope, workflowId, fallback)

        assertThat(resolved).isEqualTo(fallback)
    }

    @Test
    fun `a FAILED workspace fails instead of silently reusing another directory`() {
        assertThatThrownBy {
            resolver(listOf(WorkspaceSnapshot(WorkspaceStatus.FAILED, failureReason = "clone failed")))
                .resolve(scope, workflowId, fallback)
        }
            .isInstanceOf(WorkspaceProvisioningException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", WorkspaceProvisioningCodes.WORKSPACE_PROVISIONING_FAILED)
    }
}
