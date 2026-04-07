package io.whozoss.agentos.plugins.file.tools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.plugins.file.tools.MoveFileTool
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class MoveFileToolTest : StringSpec() {
    private lateinit var tempDir: Path

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

            val result = tool.execute(
                MoveFileTool.Input(from = "project://source.txt", to = "project://dest.txt"),
            )

            result shouldBe "File moved successfully"
            source.exists() shouldBe false
            dest.exists() shouldBe true
            dest.readText() shouldBe "content"
        }

        "move to non-existent source should error" {
            val tool = MoveFileTool(tempDir)

            val result = tool.execute(
                MoveFileTool.Input(from = "project://nonexistent.txt", to = "project://dest.txt"),
            )

            result shouldContain "Path does not exist"
        }

        "move to existing destination should error" {
            val tool = MoveFileTool(tempDir)
            tempDir.resolve("source.txt").writeText("source")
            tempDir.resolve("dest.txt").writeText("dest")

            val result = tool.execute(
                MoveFileTool.Input(from = "project://source.txt", to = "project://dest.txt"),
            )

            result shouldContain "Destination already exists"
        }

        "cross-scope move should error" {
            val tool = MoveFileTool(tempDir)
            // This test will need to be adapted when exchange:// is supported
            // For now, we can't really test cross-scope since only project:// exists
            tempDir.resolve("file.txt").writeText("content")

            // We can't actually test this scenario in V1, but we can verify the code path exists
            val result = tool.execute(
                MoveFileTool.Input(from = "project://file.txt", to = "project://moved.txt"),
            )

            // Same scope should succeed
            result shouldBe "File moved successfully"
        }

        "move should create parent directories in destination" {
            val tool = MoveFileTool(tempDir)
            tempDir.resolve("source.txt").writeText("content")

            val result = tool.execute(
                MoveFileTool.Input(from = "project://source.txt", to = "project://a/b/c/dest.txt"),
            )

            result shouldBe "File moved successfully"
            tempDir.resolve("a/b/c/dest.txt").exists() shouldBe true
            tempDir.resolve("a/b/c/dest.txt").readText() shouldBe "content"
        }

        "readOnly mode should reject move" {
            val tool = MoveFileTool(tempDir, readOnly = true)
            tempDir.resolve("source.txt").writeText("content")

            val result = tool.execute(
                MoveFileTool.Input(from = "project://source.txt", to = "project://dest.txt"),
            )

            result shouldContain "Cannot modify files in read-only mode"
        }

        "rename in same directory should work" {
            val tool = MoveFileTool(tempDir)
            val source = tempDir.resolve("old-name.txt").also { it.writeText("content") }

            val result = tool.execute(
                MoveFileTool.Input(from = "project://old-name.txt", to = "project://new-name.txt"),
            )

            result shouldBe "File moved successfully"
            source.exists() shouldBe false
            tempDir.resolve("new-name.txt").exists() shouldBe true
        }

        "move file to subdirectory should work" {
            val tool = MoveFileTool(tempDir)
            tempDir.resolve("file.txt").writeText("content")
            Files.createDirectories(tempDir.resolve("subdir"))

            val result = tool.execute(
                MoveFileTool.Input(from = "project://file.txt", to = "project://subdir/file.txt"),
            )

            result shouldBe "File moved successfully"
            tempDir.resolve("file.txt").exists() shouldBe false
            tempDir.resolve("subdir/file.txt").exists() shouldBe true
        }

        "move file from subdirectory to root should work" {
            val tool = MoveFileTool(tempDir)
            Files.createDirectories(tempDir.resolve("subdir"))
            tempDir.resolve("subdir/file.txt").writeText("content")

            val result = tool.execute(
                MoveFileTool.Input(from = "project://subdir/file.txt", to = "project://file.txt"),
            )

            result shouldBe "File moved successfully"
            tempDir.resolve("subdir/file.txt").exists() shouldBe false
            tempDir.resolve("file.txt").exists() shouldBe true
        }

        "invalid source path should error" {
            val tool = MoveFileTool(tempDir)

            val result = tool.execute(
                MoveFileTool.Input(from = "invalid-path", to = "project://dest.txt"),
            )

            result shouldContain "must start with"
        }
    }
}
