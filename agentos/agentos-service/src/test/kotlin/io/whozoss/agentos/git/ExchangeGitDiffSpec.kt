package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitExecutionProperties
import java.nio.file.Files
import java.nio.file.Path

class ExchangeGitDiffSpec : StringSpec({
    fun git(root: Path, vararg args: String): String {
        val process = ProcessBuilder("git", "-c", "user.name=Test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false", *args)
            .directory(root.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
        return output.trim()
    }
    fun fixture(): Pair<ExchangeGitDiff, ExchangeGitTarget> {
        val root = Files.createTempDirectory("exchange-diff-spec-")
        git(root, "init", "-b", "main")
        Files.writeString(root.resolve("file.txt"), "base\n")
        Files.writeString(root.resolve("removed.txt"), "deleted\n")
        git(root, "add", ".")
        git(root, "commit", "-m", "base")
        val base = git(root, "rev-parse", "HEAD")
        git(root, "update-ref", "refs/remotes/origin/main", base)
        git(root, "checkout", "-b", "feature")
        return ExchangeGitDiff(GitCommandRunner(GitExecutionProperties())) to ExchangeGitTarget(root, root.resolve(".git"), root.resolve(".git"), "main", base)
    }
    "counts committed staged unstaged and untracked changes against the branch fork" {
        val (service, target) = fixture()
        Files.writeString(target.path.resolve("file.txt"), "committed\n")
        git(target.path, "add", ".")
        git(target.path, "commit", "-m", "feature")
        Files.writeString(target.path.resolve("staged.txt"), "staged\n")
        git(target.path, "add", ".")
        Files.writeString(target.path.resolve("file.txt"), "committed\nlocal\n")
        Files.delete(target.path.resolve("removed.txt"))
        Files.writeString(target.path.resolve("new with spaces.txt"), "new\n")
        val changes = service.changes(target)
        changes.additions shouldBe 4
        changes.deletions shouldBe 2
        changes.files.size shouldBe 4
        changes.files.associate { it.path to it.status } shouldBe mapOf(
            "file.txt" to ExchangeGitFileStatus.MODIFIED,
            "staged.txt" to ExchangeGitFileStatus.ADDED,
            "removed.txt" to ExchangeGitFileStatus.DELETED,
            "new with spaces.txt" to ExchangeGitFileStatus.UNTRACKED,
        )
        service.branch(target) shouldBe "feature"
        service.file(target, "file.txt").patch shouldContain "+committed"
        service.file(target, "file.txt").patch shouldContain "+local"
        service.file(target, "new with spaces.txt").patch shouldContain "+new"
    }
    "empty files distinguish edits additions and deletions without inferring from line counts" {
        val (service, target) = fixture()
        Files.writeString(target.path.resolve("file.txt"), "")
        Files.writeString(target.path.resolve("empty\tfile.txt"), "")
        git(target.path, "add", ".")
        git(target.path, "commit", "-m", "branch edits")
        Files.delete(target.path.resolve("removed.txt"))
        val files = service.changes(target).files.associateBy { it.path }
        files.getValue("file.txt").status shouldBe ExchangeGitFileStatus.MODIFIED
        files.getValue("file.txt").additions shouldBe 0
        files.getValue("empty\tfile.txt").status shouldBe ExchangeGitFileStatus.ADDED
        files.getValue("empty\tfile.txt").additions shouldBe 0
        files.getValue("removed.txt").status shouldBe ExchangeGitFileStatus.DELETED
    }
    "a staged deletion recreated locally has one cumulative diff without changing the index" {
        val (service, target) = fixture()
        Files.writeString(target.path.resolve("file.txt"), "branch edit\n")
        git(target.path, "commit", "-am", "branch edit")
        git(target.path, "rm", "file.txt")
        Files.writeString(target.path.resolve("file.txt"), "replacement\n")
        val index = Files.readAllBytes(target.gitDir.resolve("index"))

        val changes = service.changes(target)
        changes.files shouldBe listOf(ExchangeDiffFile("file.txt", 1, 1, true, ExchangeGitFileStatus.MODIFIED))
        changes.additions shouldBe 1
        changes.deletions shouldBe 1
        val patch = service.file(target, "file.txt").patch
        patch shouldContain "-base"
        patch shouldContain "+replacement"
        patch.contains("branch edit") shouldBe false
        Files.readAllBytes(target.gitDir.resolve("index")).contentEquals(index) shouldBe true
        git(target.path, "status", "--short") shouldBe "D  file.txt\n?? file.txt"
    }
    "a recreated file identical to the branch base disappears from the cumulative diff" {
        val (service, target) = fixture()
        git(target.path, "rm", "file.txt")
        Files.writeString(target.path.resolve("file.txt"), "base\n")
        val index = Files.readAllBytes(target.gitDir.resolve("index"))

        service.changes(target).files shouldBe emptyList()
        shouldThrow<BadRequestException> { service.file(target, "file.txt") }
        Files.readAllBytes(target.gitDir.resolve("index")).contentEquals(index) shouldBe true
    }
    "recreated binary files and symbolic links have one change and no text preview" {
        val (service, target) = fixture()
        git(target.path, "rm", "file.txt", "removed.txt")
        Files.write(target.path.resolve("file.txt"), byteArrayOf(0, 1, 2))
        Files.createSymbolicLink(target.path.resolve("removed.txt"), Path.of("/etc/passwd"))
        val index = Files.readAllBytes(target.gitDir.resolve("index"))

        val changes = service.changes(target)
        changes.files.size shouldBe 2
        changes.files.single { it.path == "file.txt" }.status shouldBe ExchangeGitFileStatus.MODIFIED
        changes.files.single { it.path == "removed.txt" }.status shouldBe ExchangeGitFileStatus.TYPE_CHANGED
        service.file(target, "file.txt").message shouldBe "Binary file or symbolic link — no text preview"
        service.file(target, "removed.txt").message shouldBe "Binary file or symbolic link — no text preview"
        Files.readAllBytes(target.gitDir.resolve("index")).contentEquals(index) shouldBe true
    }
    "a recreated file still respects the patch output limit" {
        val (_, target) = fixture()
        val service = ExchangeGitDiff(GitCommandRunner(GitExecutionProperties(maxOutputChars = 256)))
        git(target.path, "rm", "file.txt")
        Files.writeString(target.path.resolve("file.txt"), "replacement\n".repeat(100))

        service.changes(target).files shouldBe listOf(ExchangeDiffFile("file.txt", 100, 1, true, ExchangeGitFileStatus.MODIFIED))
        val diff = service.file(target, "file.txt")
        diff.patch shouldBe ""
        diff.message shouldBe "This file's diff is too large to preview. Open it from the file tree."
    }
    "recreated paths containing spaces and pathspec magic are compared literally" {
        val (service, target) = fixture()
        val relative = ":(glob)[name] with spaces.txt"
        Files.writeString(target.path.resolve(relative), "original\n")
        git(target.path, "add", "--", ":(literal)$relative")
        git(target.path, "commit", "-m", "base with literal path")
        git(target.path, "update-ref", "refs/remotes/origin/main", "HEAD")
        git(target.path, "rm", "--", ":(literal)$relative")
        Files.writeString(target.path.resolve(relative), "replacement\n")

        service.changes(target).files shouldBe listOf(ExchangeDiffFile(relative, 1, 1, true, ExchangeGitFileStatus.MODIFIED))
        val patch = service.file(target, relative).patch
        patch shouldContain "-original"
        patch shouldContain "+replacement"
    }
    "unresolved merges are marked as conflicts" {
        val (service, target) = fixture()
        git(target.path, "switch", "main")
        Files.writeString(target.path.resolve("file.txt"), "main edit\n")
        git(target.path, "commit", "-am", "main edit")
        git(target.path, "switch", "feature")
        Files.writeString(target.path.resolve("file.txt"), "feature edit\n")
        git(target.path, "commit", "-am", "feature edit")
        val merge = ProcessBuilder("git", "-c", "user.name=Test", "-c", "user.email=test@example.com", "merge", "main")
            .directory(target.path.toFile()).redirectErrorStream(true).start()
        merge.inputStream.bufferedReader().readText()
        merge.waitFor() shouldBe 1
        service.changes(target).files.single { it.path == "file.txt" }.status shouldBe ExchangeGitFileStatus.CONFLICTED
    }
    "the fetched case base takes precedence over an older origin main ref" {
        val (service, target) = fixture()
        Files.writeString(target.path.resolve("upstream.txt"), "upstream update\n")
        git(target.path, "add", ".")
        git(target.path, "commit", "-m", "new upstream base")
        val currentBase = git(target.path, "rev-parse", "HEAD")
        Files.writeString(target.path.resolve("feature.txt"), "case work\n")
        val changes = service.changes(target.copy(fallbackBase = currentBase))
        changes.files.map { it.path } shouldBe listOf("feature.txt")
        changes.additions shouldBe 1
    }
    "detached worktrees have no branch and no artificial changes" {
        val (service, target) = fixture()
        git(target.path, "checkout", "--detach")
        service.branch(target) shouldBe null
        service.changes(target).files.size shouldBe 0
    }
    "binary files and links have no text preview and path traversal is refused" {
        val (service, target) = fixture()
        Files.write(target.path.resolve("image.bin"), byteArrayOf(0, 1, 2))
        Files.createSymbolicLink(target.path.resolve("external"), Path.of("/etc/passwd"))
        service.file(target, "image.bin").patch shouldBe ""
        service.file(target, "external").patch shouldBe ""
        shouldThrow<BadRequestException> { service.file(target, "../secret") }
        shouldThrow<BadRequestException> { service.file(target, ".git/config") }
    }
    "a filename containing pathspec magic is inspected literally" {
        val (service, target) = fixture()
        Files.writeString(target.path.resolve("[name].txt"), "literal\n")
        git(target.path, "add", ".")
        service.file(target, "[name].txt").patch shouldContain "+literal"
    }
})
