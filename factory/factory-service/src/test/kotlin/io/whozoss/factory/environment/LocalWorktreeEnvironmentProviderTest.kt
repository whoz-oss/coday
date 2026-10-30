package io.whozoss.factory.environment

import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.port.EnvironmentGitInspectionResult
import io.whozoss.factory.environment.port.GitCommandResult
import io.whozoss.factory.environment.port.GitCommandRunner
import io.whozoss.factory.environment.port.LocalWorktreeEnvironmentProvider
import io.whozoss.factory.environment.port.PrepareEnvironmentBranchCommand
import io.whozoss.factory.environment.port.PrepareEnvironmentBranchResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalWorktreeEnvironmentProviderTest {
    private val head = "a".repeat(40)

    @Test
    fun `inspect reports head branch and dirty tracked and untracked baseline`() {
        val root = root(mapOf("tracked.yml" to "local", "agent.yml" to "secret"))
        val runner = scripted(root, " M tracked.yml\u0000?? agent.yml\u0000")
        val inspection = assertIs<EnvironmentGitInspectionResult.Inspected>(
            LocalWorktreeEnvironmentProvider(runner.runner).inspect(environment(root), 3),
        ).inspection
        assertEquals(head, inspection.head)
        assertEquals("main", inspection.currentBranch)
        assertTrue(inspection.dirty)
        assertEquals(2, inspection.baselinePathCount)
        assertEquals(listOf("agent.yml", "tracked.yml"), inspection.baselinePaths)
    }

    @Test
    fun `prepare accepts unchanged inspection and replay is idempotent`() {
        val root = root(mapOf("local.yml" to "same"))
        val script = scripted(root, "?? local.yml\u0000")
        val provider = LocalWorktreeEnvironmentProvider(script.runner)
        val env = environment(root)
        val inspection = assertIs<EnvironmentGitInspectionResult.Inspected>(provider.inspect(env, 3)).inspection
        val command = command(inspection, "factory/WZ-33157")
        assertIs<PrepareEnvironmentBranchResult.Prepared>(provider.prepareBranch(env, command))
        // The first switch changes currentBranch, so replay must start from a fresh
        // provider inspection/token; reusing the pre-switch token is correctly drift.
        val replayInspection = assertIs<EnvironmentGitInspectionResult.Inspected>(provider.inspect(env, 3)).inspection
        assertEquals("factory/WZ-33157", replayInspection.currentBranch)
        assertIs<PrepareEnvironmentBranchResult.Prepared>(
            provider.prepareBranch(env, command(replayInspection, "factory/WZ-33157")),
        )
        assertEquals(1, script.calls.count { it.firstOrNull() == "switch" })
    }

    @Test
    fun `head or baseline drift refuses before switch`() {
        val root = root(mapOf("local.yml" to "before"))
        val script = scripted(root, " M local.yml\u0000")
        val provider = LocalWorktreeEnvironmentProvider(script.runner)
        val env = environment(root)
        val inspection = assertIs<EnvironmentGitInspectionResult.Inspected>(provider.inspect(env, 3)).inspection
        Files.writeString(root.resolve("local.yml"), "after")
        val refused = assertIs<PrepareEnvironmentBranchResult.Refused>(
            provider.prepareBranch(env, command(inspection, "factory/WZ-33157")),
        )
        assertEquals("inspection_drift", refused.facts.failureStage)
        assertTrue(script.calls.none { it.firstOrNull() == "switch" })
    }

    @Test
    fun `postcondition mutation is failed and forbidden git operations are absent`() {
        val root = root(mapOf("local.yml" to "before"))
        val script = scripted(root, " M local.yml\u0000", mutateOnSwitch = true)
        val provider = LocalWorktreeEnvironmentProvider(script.runner)
        val env = environment(root)
        val inspection = assertIs<EnvironmentGitInspectionResult.Inspected>(provider.inspect(env, 3)).inspection
        val refused = assertIs<PrepareEnvironmentBranchResult.Refused>(
            provider.prepareBranch(env, command(inspection, "factory/WZ-33157")),
        )
        assertEquals("postcondition_failed", refused.facts.failureStage)
        assertTrue(refused.facts.mutationApplied)
        assertTrue(script.calls.none { it.firstOrNull() in setOf("stash", "reset", "add", "commit", "push") })
    }

    private fun command(inspection: io.whozoss.factory.environment.port.EnvironmentGitInspection, branch: String) =
        PrepareEnvironmentBranchCommand(inspection.environmentId, inspection.environmentRevision, inspection.head,
            inspection.baselineHash, inspection.inspectionToken, branch)

    private fun environment(root: Path) = WorkEnvironment(
        organizationId = "org", workstreamId = "ws", environmentId = "env-1", workUnitId = "wu-1",
        workflowId = "wf-1", namespaceId = "ns", repoRoot = root.toString(), integrationBranch = "main",
        branch = "initial", worktreePath = root.toString(), createdBy = "test", revision = 3,
    )

    private data class Script(val runner: GitCommandRunner, val calls: MutableList<List<String>>)
    private fun scripted(root: Path, status: String, mutateOnSwitch: Boolean = false): Script {
        val calls = mutableListOf<List<String>>(); var branch = "main"
        val runner = GitCommandRunner { _, args ->
            calls += args
            when {
                args == listOf("rev-parse", "HEAD") -> GitCommandResult(0, head, "")
                args == listOf("symbolic-ref", "--quiet", "--short", "HEAD") -> GitCommandResult(0, branch, "")
                args == listOf("status", "--porcelain=v1", "-z", "--untracked-files=all") -> GitCommandResult(0, status, "")
                args.take(2) == listOf("rev-parse", "--verify") -> GitCommandResult(1, "", "missing")
                args.take(2) == listOf("switch", "-c") -> {
                    branch = args[2]
                    if (mutateOnSwitch) Files.writeString(root.resolve("local.yml"), "mutated")
                    GitCommandResult(0, "", "")
                }
                args.firstOrNull() == "switch" -> { branch = args[1]; GitCommandResult(0, "", "") }
                else -> error("unexpected Git args: $args")
            }
        }
        return Script(runner, calls)
    }

    private fun root(files: Map<String, String>): Path = Files.createTempDirectory("local-provider").also { root ->
        Files.createFile(root.resolve(".git")); files.forEach { (name, content) -> Files.writeString(root.resolve(name), content) }
    }
}
