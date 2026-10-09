package io.whozoss.factory.workspace

import io.whozoss.factory.persistence.TenantScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Pure unit tests of the workspace availability waiter (Lot F).
 *
 * The clock and the sleep are injected, so the whole PREPARING -> READY ->
 * FAILED/timeout state machine is exercised deterministically, with no real
 * wait. The waiter never holds a transaction (it is not `@Transactional`) and
 * never falls back to another directory.
 */
class WorkspaceStateWaiterTest {

    private val scope = TenantScope("org", "ws")
    private val workflowId = "wf-1"

    /** Returns each snapshot in order, repeating the last one for further reads. */
    private class ScriptedStatePort(private val snapshots: List<WorkspaceSnapshot?>) : WorkspaceStatePort {
        private var index = 0
        override fun latestForWorkflow(scope: TenantScope, workflowId: String): WorkspaceSnapshot? {
            val snapshot = snapshots[minOf(index, snapshots.lastIndex)]
            index++
            return snapshot
        }
    }

    private fun waiter(snapshots: List<WorkspaceSnapshot?>, enabled: Boolean = true): WorkspaceStateWaiter =
        WorkspaceStateWaiter(
            ScriptedStatePort(snapshots),
            WorkspaceProperties(enabled = enabled, readyTimeoutMs = 1_000, readyPollIntervalMs = 10),
        )

    @Test
    fun `PREPARING then READY returns the proven worktree path`() {
        val subject = waiter(
            listOf(
                WorkspaceSnapshot(WorkspaceStatus.PREPARING, caseId = "root-case"),
                WorkspaceSnapshot(WorkspaceStatus.READY, worktreePath = "/worktrees/wf-1", caseId = "root-case"),
            ),
        )

        val facts = subject.poll(scope, workflowId, timeoutMs = 1_000, pollIntervalMs = 10, now = { 0L }, sleep = {})

        assertThat(facts).isNotNull
        assertThat(facts!!.worktreePath).isEqualTo("/worktrees/wf-1")
        assertThat(facts.caseId).isEqualTo("root-case")
    }

    @Test
    fun `a PREPARING workspace timing out fails with WORKSPACE_PREPARING_TIMEOUT`() {
        val subject = waiter(listOf(WorkspaceSnapshot(WorkspaceStatus.PREPARING)))
        var clock = 0L

        assertThatThrownBy {
            subject.poll(scope, workflowId, timeoutMs = 100, pollIntervalMs = 10, now = { clock }, sleep = { clock += 10 })
        }
            .isInstanceOf(WorkspaceProvisioningException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", WorkspaceProvisioningCodes.WORKSPACE_PREPARING_TIMEOUT)
    }

    @Test
    fun `a FAILED workspace fails immediately and never returns another directory`() {
        val subject = waiter(
            listOf(WorkspaceSnapshot(WorkspaceStatus.FAILED, worktreePath = "/worktrees/wf-1", failureReason = "clone failed")),
        )

        assertThatThrownBy {
            subject.poll(scope, workflowId, timeoutMs = 1_000, pollIntervalMs = 10, now = { 0L }, sleep = {})
        }
            .isInstanceOf(WorkspaceProvisioningException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", WorkspaceProvisioningCodes.WORKSPACE_PROVISIONING_FAILED)
            .hasMessageContaining("clone failed")
    }

    @Test
    fun `a READY workspace without a worktree path fails with WORKSPACE_PATH_MISSING`() {
        val subject = waiter(listOf(WorkspaceSnapshot(WorkspaceStatus.READY, worktreePath = " ")))

        assertThatThrownBy {
            subject.poll(scope, workflowId, timeoutMs = 1_000, pollIntervalMs = 10, now = { 0L }, sleep = {})
        }
            .isInstanceOf(WorkspaceProvisioningException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", WorkspaceProvisioningCodes.WORKSPACE_PATH_MISSING)
    }

    @Test
    fun `no requested workspace returns null so the caller keeps its explicit root`() {
        val subject = waiter(listOf(null))

        val facts = subject.poll(scope, workflowId, timeoutMs = 1_000, pollIntervalMs = 10, now = { 0L }, sleep = {})

        assertThat(facts).isNull()
    }

    @Test
    fun `a disabled waiter resolves nothing`() {
        val subject = waiter(listOf(WorkspaceSnapshot(WorkspaceStatus.READY, worktreePath = "/worktrees/wf-1")), enabled = false)

        assertThat(subject.awaitWorkspaceReady(scope, workflowId)).isNull()
    }
}
