package io.whozoss.agentos.plugins.file.tools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.plugins.file.SensitiveFilePatterns
import io.whozoss.agentos.sdk.tool.ToolContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class MoveFileToolSpec : StringSpec() {
    private lateinit var tempDir: Path
    private val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())

    init {
        beforeEach {
            tempDir = Files.createTempDirectory("test")
        }

        afterEach {
            tempDir.toFile().deleteRecursively()
        }

        "move file should succeed and preserve content" {
            val tool = MoveFileTool(tempDir)
            val source = tempDir.resolve("source.txt").also { it.writeText("content") }
            val dest = tempDir.resolve("dest.txt")

            val result = tool.execute(MoveFileTool.Input(from = "source.txt", to = "dest.txt"), ctx)

            result.success shouldBe true
            result.output shouldBe "File moved successfully"
            source.exists() shouldBe false
            dest.exists() shouldBe true
            dest.readText() shouldBe "content"
        }

        "move to non-existent source should error" {
            val tool = MoveFileTool(tempDir)

            val result = tool.execute(MoveFileTool.Input(from = "nonexistent.txt", to = "dest.txt"), ctx)

            result.success shouldBe false
            result.output shouldContain "Path does not exist"
        }

        "move to existing destination should error" {
            val tool = MoveFileTool(tempDir)
            tempDir.resolve("source.txt").writeText("source")
            tempDir.resolve("dest.txt").writeText("dest")

            val result = tool.execute(MoveFileTool.Input(from = "source.txt", to = "dest.txt"), ctx)

            // Destination already exists is returned from moveFile() as a plain string — success=true
            result.output shouldContain "Destination already exists"
        }

        "move should create parent directories in destination" {
            val tool = MoveFileTool(tempDir)
            tempDir.resolve("source.txt").writeText("content")

            val result = tool.execute(MoveFileTool.Input(from = "source.txt", to = "a/b/c/dest.txt"), ctx)

            result.success shouldBe true
            result.output shouldBe "File moved successfully"
            tempDir.resolve("a/b/c/dest.txt").exists() shouldBe true
            tempDir.resolve("a/b/c/dest.txt").readText() shouldBe "content"
        }

        "rename in same directory should work" {
            val tool = MoveFileTool(tempDir)
            val source = tempDir.resolve("old-name.txt").also { it.writeText("content") }

            val result = tool.execute(MoveFileTool.Input(from = "old-name.txt", to = "new-name.txt"), ctx)

            result.success shouldBe true
            result.output shouldBe "File moved successfully"
            source.exists() shouldBe false
            tempDir.resolve("new-name.txt").exists() shouldBe true
        }

        "move file to subdirectory should work" {
            val tool = MoveFileTool(tempDir)
            tempDir.resolve("file.txt").writeText("content")
            Files.createDirectories(tempDir.resolve("subdir"))

            val result = tool.execute(MoveFileTool.Input(from = "file.txt", to = "subdir/file.txt"), ctx)

            result.success shouldBe true
            result.output shouldBe "File moved successfully"
            tempDir.resolve("file.txt").exists() shouldBe false
            tempDir.resolve("subdir/file.txt").exists() shouldBe true
        }

        "move file from subdirectory to root should work" {
            val tool = MoveFileTool(tempDir)
            Files.createDirectories(tempDir.resolve("subdir"))
            tempDir.resolve("subdir/file.txt").writeText("content")

            val result = tool.execute(MoveFileTool.Input(from = "subdir/file.txt", to = "file.txt"), ctx)

            result.success shouldBe true
            result.output shouldBe "File moved successfully"
            tempDir.resolve("subdir/file.txt").exists() shouldBe false
            tempDir.resolve("file.txt").exists() shouldBe true
        }

        "path traversal in source should error" {
            val tool = MoveFileTool(tempDir)

            val result = tool.execute(MoveFileTool.Input(from = "../outside.txt", to = "dest.txt"), ctx)

            result.success shouldBe false
            result.output shouldContain "path traversal not allowed"
        }

        "move a populated ordinary directory outside Git and create destination parents" {
            val source = Files.createDirectories(tempDir.resolve("source/nested"))
            source.resolve("notes.txt").writeText("keep this content")

            val result = MoveFileTool(tempDir).execute(MoveFileTool.Input("source", "archive/renamed"), ctx)

            result.success shouldBe true
            tempDir.resolve("source").exists() shouldBe false
            tempDir.resolve("archive/renamed/nested/notes.txt").readText() shouldBe "keep this content"
        }

        "move an empty ordinary directory" {
            Files.createDirectory(tempDir.resolve("empty"))
            val result = MoveFileTool(tempDir).execute(MoveFileTool.Input("empty", "renamed"), ctx)
            result.success shouldBe true
            Files.isDirectory(tempDir.resolve("renamed")) shouldBe true
            tempDir.resolve("empty").exists() shouldBe false
        }

        "Git metadata protection follows the configured exclusions outside Exchange" {
            val source = Files.createDirectories(tempDir.resolve("ordinary"))
            source.resolve(".git").writeText("generic file scope")

            val result = MoveFileTool(tempDir, denyPatterns = emptyList())
                .execute(MoveFileTool.Input("ordinary", "renamed"), ctx)

            result.success shouldBe true
            tempDir.resolve("renamed/.git").readText() shouldBe "generic file scope"
        }

        (SensitiveFilePatterns.DEFAULT_PATTERNS + listOf(".git", "*.custom")).forEach { pattern ->
            val protectedName = pattern.replace("*", "secret").uppercase()
            "directory move checks case-insensitive descendant exclusion $pattern" {
                val source = Files.createDirectories(tempDir.resolve("ordinary/nested"))
                source.resolve(protectedName).writeText("protected content")
                val tool = MoveFileTool(tempDir, denyPatterns = listOf(pattern))

                val result = tool.execute(MoveFileTool.Input("ordinary", "new-parent/renamed"), ctx)

                result.success shouldBe false
                result.output shouldContain "Access denied"
                source.resolve(protectedName).readText() shouldBe "protected content"
                tempDir.resolve("new-parent").exists() shouldBe false
            }
        }

        "move refuses nested Git directories as well as worktree pointer files" {
            val metadata = Files.createDirectories(tempDir.resolve("ordinary/nested/.GiT"))
            metadata.resolve("config").writeText("repository settings")
            val tool = MoveFileTool(tempDir, denyPatterns = listOf(".git"))

            val result = tool.execute(MoveFileTool.Input("ordinary", "archive/renamed"), ctx)

            result.success shouldBe false
            metadata.resolve("config").readText() shouldBe "repository settings"
            tempDir.resolve("archive").exists() shouldBe false
        }

        "directory move preserves links to ordinary files within the moved tree" {
            val source = Files.createDirectories(tempDir.resolve("source/nested"))
            source.resolve("target.txt").writeText("linked content")
            Files.createSymbolicLink(source.resolve("link.txt"), Path.of("target.txt"))

            val result = MoveFileTool(tempDir).execute(MoveFileTool.Input("source", "archive/renamed"), ctx)

            result.success shouldBe true
            val link = tempDir.resolve("archive/renamed/nested/link.txt")
            Files.isSymbolicLink(link) shouldBe true
            link.readText() shouldBe "linked content"
        }

        "directory move preserves absolute links to ordinary files within the root" {
            tempDir.resolve("target.txt").writeText("linked content")
            val source = Files.createDirectories(tempDir.resolve("source"))
            Files.createSymbolicLink(source.resolve("link.txt"), tempDir.resolve("target.txt"))

            val result = MoveFileTool(tempDir).execute(MoveFileTool.Input("source", "archive/renamed"), ctx)

            result.success shouldBe true
            val link = tempDir.resolve("archive/renamed/link.txt")
            Files.isSymbolicLink(link) shouldBe true
            link.readText() shouldBe "linked content"
        }

        "directory move refuses a descendant link escaping the configured root" {
            val outside = Files.createTempFile("outside-move", ".txt")
            try {
                outside.writeText("outside content")
                val source = Files.createDirectory(tempDir.resolve("source"))
                Files.createSymbolicLink(source.resolve("link.txt"), outside)

                val result = MoveFileTool(tempDir).execute(MoveFileTool.Input("source", "archive/renamed"), ctx)

                result.success shouldBe false
                result.output shouldContain "Symlink escapes boundary"
                source.exists() shouldBe true
                outside.readText() shouldBe "outside content"
                tempDir.resolve("archive").exists() shouldBe false
            } finally {
                Files.deleteIfExists(outside)
            }
        }

        "directory move refuses a link whose new relative target would escape the root" {
            tempDir.resolve("target.txt").writeText("inside content")
            val source = Files.createDirectories(tempDir.resolve("nested/source"))
            Files.createSymbolicLink(source.resolve("link.txt"), Path.of("../../target.txt"))

            val result = MoveFileTool(tempDir).execute(MoveFileTool.Input("nested/source", "renamed"), ctx)

            result.success shouldBe false
            result.output shouldContain "A moved symlink would escape"
            source.resolve("link.txt").readText() shouldBe "inside content"
            tempDir.resolve("renamed").exists() shouldBe false
        }

        "directory move resolves intermediate links before parent traversal in projected targets" {
            tempDir.resolve("target.txt").writeText("inside content")
            val source = Files.createDirectories(tempDir.resolve("nested/source/deep"))
            source.parent.resolve("target.txt").writeText("normalized target")
            Files.createSymbolicLink(source.resolve("via"), Path.of(".."))
            Files.createSymbolicLink(source.resolve("link"), Path.of("via/../../target.txt"))
            source.resolve("link").readText() shouldBe "inside content"

            val result = MoveFileTool(tempDir).execute(MoveFileTool.Input("nested/source", "renamed"), ctx)

            result.success shouldBe false
            result.output shouldContain "A moved symlink would escape"
            source.resolve("link").readText() shouldBe "inside content"
            tempDir.resolve("renamed").exists() shouldBe false
        }

        "directory move refuses a descendant alias to protected metadata" {
            Files.createDirectories(tempDir.resolve("repository/.git"))
            tempDir.resolve("repository/.git/config").writeText("protected metadata")
            val source = Files.createDirectory(tempDir.resolve("ordinary"))
            Files.createSymbolicLink(source.resolve("settings"), tempDir.resolve("repository/.git/config"))

            val result = MoveFileTool(tempDir, denyPatterns = listOf(".git"))
                .execute(MoveFileTool.Input("ordinary", "archive/renamed"), ctx)

            result.success shouldBe false
            result.output shouldContain "Access denied"
            source.exists() shouldBe true
            tempDir.resolve("archive").exists() shouldBe false
        }

        "directory move refuses destinations inside the source before creating parents" {
            val source = Files.createDirectory(tempDir.resolve("ordinary"))
            source.resolve("file.txt").writeText("keep")

            val result = MoveFileTool(tempDir).execute(MoveFileTool.Input("ordinary", "ordinary/new/renamed"), ctx)

            result.success shouldBe false
            source.resolve("file.txt").readText() shouldBe "keep"
            source.resolve("new").exists() shouldBe false
        }

        "move cannot relocate a repository directory or create destination parents" {
            val repo = Files.createDirectory(tempDir.resolve("repo"))
            repo.resolve(".git").writeText("gitdir: /fixture/worktrees/case")
            repo.resolve("README.md").writeText("work in progress")

            val tool = MoveFileTool(tempDir, denyPatterns = SensitiveFilePatterns.DEFAULT_PATTERNS + ".git")
            val result = tool.execute(MoveFileTool.Input("repo", "backup/repo"), ctx)

            result.success shouldBe false
            result.output shouldContain "Access denied"
            repo.resolve(".git").readText() shouldBe "gitdir: /fixture/worktrees/case"
            repo.resolve("README.md").readText() shouldBe "work in progress"
            tempDir.resolve("backup").exists() shouldBe false
        }
    }
}
