package io.whozoss.factory.workspace

import io.whozoss.factory.environment.port.GitCommandResult
import io.whozoss.factory.environment.port.GitCommandRunner
import io.whozoss.factory.persistence.TenantScope
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RunWorktreeResolverTest {
    @TempDir lateinit var temp: Path
    private val scope = TenantScope("org", "ws")
    private val head = "a".repeat(40)
    private fun resolver(
        snapshot: WorkspaceSnapshot?, branch: String = "factory/ticket", enabled: Boolean = true,
        ancestor: Boolean = true,
    ): RunWorktreeResolver {
        val properties = WorkspaceProperties(enabled = enabled)
        val port = object : WorkspaceStatePort {
            override fun latestForWorkflow(scope: TenantScope, workflowId: String) = snapshot
        }
        val git = GitCommandRunner { root, args ->
            GitCommandResult(when {
                args.first() == "merge-base" && !ancestor -> 1
                args.first() == "symbolic-ref" && branch.isEmpty() -> 1
                else -> 0
            }, when (args.first()) {
                "symbolic-ref" -> branch
                "merge-base" -> ""
                else -> if ("--show-toplevel" in args) root.toString() else head
            }, "")
        }
        return RunWorktreeResolver(WorkspaceStateWaiter(port, properties), git, properties)
    }
    private fun ready(): WorkspaceSnapshot {
        val root = Files.createDirectories(temp.resolve("worktrees/run"))
        Files.createFile(root.resolve(".git")) // linked-worktree marker; Git inspection is stubbed
        return WorkspaceSnapshot(WorkspaceStatus.READY, root.toString(), environmentId = "env", namespaceId = "ns", caseId = "case")
    }
    @Test fun `persisted worktree wins over namespace parent and submission checkout`() {
        val snapshot = ready()
        val binding = resolver(snapshot).resolveBinding(scope, "run", temp.resolve("namespace"), "ns", expectedCaseId = "case")
        assertThat(binding.root).isEqualTo(Path.of(snapshot.worktreePath!!).toRealPath())
        assertThat(binding.provenance).isEqualTo("run-environment")
        assertThat(binding.head).isEqualTo(head)
    }
    @Test fun `parent run and standalone ticket delivery without environment cannot use submission root`() {
        for (owner in listOf("run", "standalone-ticket-delivery")) {
            assertThatThrownBy { resolver(null).resolveBinding(scope, owner, temp) }
                .hasMessageContaining("CODE_WORKSPACE_REQUIRED")
        }
    }
    @Test fun `disabled workspace is the only explicit fallback`() {
        val binding = resolver(null, enabled = false).resolveBinding(scope, "wf", temp)
        assertThat(binding.provenance).isEqualTo("workspace-disabled")
        assertThat(binding.facts()["workspaceChecksEnabled"]).isEqualTo(false)
    }
    @Test fun `provisioned detached worktree may attach later without a stale branch expectation`() {
        val snapshot = ready().copy(baseCommit = head)
        assertThat(resolver(snapshot, "").resolveBinding(scope, "wf", temp, requireBranch = false).branch).isNull()
        val attached = resolver(snapshot, "work/forge/ticket").resolveBinding(scope, "wf", temp)
        assertThat(attached.branch).isEqualTo("work/forge/ticket")
        assertThat(attached.facts()["branchExpectationSource"]).isEqualTo("unavailable-attached-only")
        assertThatThrownBy { resolver(snapshot, "").resolveBinding(scope, "wf", temp) }
            .hasMessageContaining("CODE_WORKSPACE_BRANCH_MISMATCH")
    }
    @Test fun `reliable control plane expectation is checked instead of provisioning observation`() {
        val snapshot = ready()
        val expected: (String?) -> String? = { environment ->
            assertThat(environment).isEqualTo("env")
            "factory/ticket"
        }
        assertThat(resolver(snapshot).resolveBinding(scope, "wf", temp, branchExpectation = expected).expectedBranch)
            .isEqualTo("factory/ticket")
        assertThatThrownBy { resolver(snapshot, "other").resolveBinding(scope, "wf", temp, branchExpectation = expected) }
            .hasMessageContaining("CODE_WORKSPACE_BRANCH_MISMATCH")
    }
    @Test fun `namespace and case mismatches fail`() {
        val snapshot = ready()
        assertThatThrownBy { resolver(snapshot).resolveBinding(scope, "run", temp, "other") }
            .hasMessageContaining("namespace mismatch")
        assertThatThrownBy { resolver(snapshot).resolveBinding(scope, "run", temp, expectedCaseId = "other") }
            .hasMessageContaining("root case mismatch")
    }
    @Test fun `standalone environment without root ownership is refused after root case reservation`() {
        val snapshot = ready().copy(caseId = null)
        assertThatThrownBy {
            resolver(snapshot).resolveBinding(scope, "standalone", temp, "ns", expectedCaseId = "reserved-root")
        }.hasMessageContaining("root case mismatch")
    }
    @Test fun `divergent HEAD and missing worktree fail without fallback`() {
        val snapshot = ready().copy(baseCommit = "b".repeat(40))
        assertThatThrownBy { resolver(snapshot, ancestor = false).resolve(scope, "run", temp) }
            .hasMessageContaining("CODE_WORKSPACE_HEAD_MISMATCH")
        assertThatThrownBy { resolver(snapshot.copy(worktreePath = temp.resolve("missing").toString())).resolve(scope, "run", temp) }
            .hasMessageContaining("CODE_WORKSPACE_INVALID")
    }
    @Test fun `workspace preparation is waited for before binding`() {
        val snapshot = ready()
        var polls = 0
        val port = object : WorkspaceStatePort {
            override fun latestForWorkflow(scope: TenantScope, workflowId: String): WorkspaceSnapshot =
                if (polls++ == 0) WorkspaceSnapshot(WorkspaceStatus.PREPARING) else snapshot
        }
        val waiter = WorkspaceStateWaiter(port, WorkspaceProperties(readyPollIntervalMs = 1))
        assertThat(waiter.awaitWorkspaceReady(scope, "run")?.worktreePath).isEqualTo(snapshot.worktreePath)
        assertThat(polls).isEqualTo(2)
    }
    @Test fun `failed workspace never falls back`() {
        assertThatThrownBy { resolver(WorkspaceSnapshot(WorkspaceStatus.FAILED)).resolve(scope, "run", temp) }
            .isInstanceOf(WorkspaceProvisioningException::class.java)
    }
}
