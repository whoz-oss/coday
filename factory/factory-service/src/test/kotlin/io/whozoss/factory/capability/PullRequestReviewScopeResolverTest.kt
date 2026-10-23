package io.whozoss.factory.capability

import io.mockk.every
import io.mockk.mockk
import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.environment.port.GitCommandResult
import io.whozoss.factory.environment.port.GitCommandRunner
import io.whozoss.factory.persistence.TenantScope
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PullRequestReviewScopeResolverTest {
    private val scope = TenantScope("org", "ws")
    private val base = "a".repeat(40)

    @Test
    fun `classifies added modified renamed and untracked files and excludes deletions`() {
        val root = Files.createTempDirectory("a11y-pr-scope")
        Files.createFile(root.resolve(".git"))
        val repository = mockk<WorkEnvironmentRepository>()
        every { repository.findLatestByWorkflowId(scope, "wf") } returns environment(root.toString())
        val runner = GitCommandRunner { _, args ->
            when {
                args.take(2) == listOf("cat-file", "-e") -> GitCommandResult(0, "", "")
                args.take(2) == listOf("diff", "--name-status") -> GitCommandResult(
                    0,
                    "A\u0000new.html\u0000M\u0000changed.ts\u0000R100\u0000old.scss\u0000renamed.scss\u0000D\u0000deleted.html\u0000",
                    "",
                )
                args.take(2) == listOf("ls-files", "--others") -> GitCommandResult(0, "untracked.html\u0000", "")
                args.take(2) == listOf("diff", "--unified=0") && args.last() == "changed.ts" ->
                    GitCommandResult(0, "@@ -3 +3,2 @@\n", "")
                args.take(2) == listOf("diff", "--unified=0") && args.last() == "renamed.scss" ->
                    GitCommandResult(0, "@@ -1 +1 @@\n", "")
                else -> error("Unexpected git arguments: $args")
            }
        }

        val result = assertIs<PullRequestReviewScopeResult.Resolved>(
            PullRequestReviewScopeResolver(repository, runner).resolve(scope, "wf"),
        ).scope

        assertEquals(listOf("new.html", "untracked.html"), result.createdFiles)
        assertEquals(listOf("changed.ts", "renamed.scss"), result.modifiedFiles)
        assertEquals(listOf(3..4), result.changedLines.getValue("changed.ts"))
        assertEquals(listOf(1..1), result.changedLines.getValue("renamed.scss"))
    }

    @Test
    fun `fails closed when frozen base is absent`() {
        val repository = mockk<WorkEnvironmentRepository>()
        every { repository.findLatestByWorkflowId(scope, "wf") } returns environment("/tmp", baseCommit = null)

        val result = PullRequestReviewScopeResolver(repository, GitCommandRunner { _, _ -> error("must not run") })
            .resolve(scope, "wf")

        assertIs<PullRequestReviewScopeResult.Unavailable>(result)
    }

    @Test
    fun `fails closed when changed line ranges cannot be established`() {
        val root = Files.createTempDirectory("a11y-pr-lines")
        Files.createFile(root.resolve(".git"))
        val repository = mockk<WorkEnvironmentRepository>()
        every { repository.findLatestByWorkflowId(scope, "wf") } returns environment(root.toString())
        val runner = GitCommandRunner { _, args ->
            when {
                args.take(2) == listOf("cat-file", "-e") -> GitCommandResult(0, "", "")
                args.take(2) == listOf("diff", "--name-status") -> GitCommandResult(0, "M\u0000changed.ts\u0000", "")
                args.take(2) == listOf("ls-files", "--others") -> GitCommandResult(0, "", "")
                args.take(2) == listOf("diff", "--unified=0") -> GitCommandResult(0, "malformed", "")
                else -> error("Unexpected git arguments: $args")
            }
        }

        val result = PullRequestReviewScopeResolver(repository, runner).resolve(scope, "wf")

        assertIs<PullRequestReviewScopeResult.Unavailable>(result)
    }

    private fun environment(path: String, baseCommit: String? = base) = WorkEnvironment(
        organizationId = "org",
        workstreamId = "ws",
        environmentId = "env",
        workUnitId = "unit",
        workflowId = "wf",
        namespaceId = "ns",
        repoRoot = path,
        integrationBranch = "main",
        branch = "factory/WZ-1",
        worktreePath = path,
        baseCommit = baseCommit,
        createdBy = "test",
    )
}
