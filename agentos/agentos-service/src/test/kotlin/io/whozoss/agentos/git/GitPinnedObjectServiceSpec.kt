package io.whozoss.agentos.git

import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitExecutionProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class GitPinnedObjectServiceSpec {
    @TempDir lateinit var exchange: Path

    @Test
    fun `reads the named commit bytes after HEAD moves and ignores local replacement refs`() {
        val repository = exchange.resolve("repo")
        Files.createDirectories(repository)
        git(repository, "init", "-q")
        git(repository, "config", "user.name", "Git reader test")
        git(repository, "config", "user.email", "git-reader@example.invalid")
        val original = byteArrayOf(0, 1, -1, 10)
        Files.write(repository.resolve("packet.bin"), original)
        git(repository, "add", "packet.bin")
        git(repository, "commit", "-qm", "approved packet")
        val pinned = git(repository, "rev-parse", "HEAD").trim()
        Files.write(repository.resolve("packet.bin"), byteArrayOf(4, 5, 6))
        git(repository, "add", "packet.bin")
        git(repository, "commit", "-qm", "later packet")
        val later = git(repository, "rev-parse", "HEAD").trim()
        val workBranch = git(repository, "branch", "--show-current").trim()
        git(repository, "checkout", "--orphan", "unrelated-packet")
        Files.write(repository.resolve("packet.bin"), byteArrayOf(9))
        git(repository, "add", "packet.bin")
        git(repository, "commit", "-qm", "unrelated history")
        val unrelated = git(repository, "rev-parse", "HEAD").trim()
        git(repository, "checkout", workBranch)
        git(repository, "replace", pinned, later)
        val binding = CaseResourceBinding(
            rootCaseId = UUID.randomUUID(), namespaceId = UUID.randomUUID(),
            integrationConfigId = UUID.randomUUID(), status = CaseResourceStatus.READY,
        )
        val status = mockk<GitWorkspaceStatusService>()
        every { status.worktreeGitDir(binding) } returns repository.resolve(".git")
        val reader = GitPinnedObjectService(GitCommandRunner(GitExecutionProperties()), status)
        val root = GitExchangeRoot(exchange, binding, binding.rootCaseId)

        reader.requireCommit(root, pinned)
        assertThat(reader.readBlob(root, pinned, "packet.bin")).containsExactly(*original)
        assertThatThrownBy { reader.readBlob(root, pinned, "../packet.bin") }
            .isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { reader.readBlob(root, "not-a-commit", "packet.bin") }
            .isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { reader.requireCommit(root, "f".repeat(40)) }
            .isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { reader.requireCommit(root, unrelated) }
            .isInstanceOf(ConflictException::class.java)
    }

    private fun git(repository: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repository.toString()) + args)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "Git fixture failed: $output" }
        return output
    }
}
