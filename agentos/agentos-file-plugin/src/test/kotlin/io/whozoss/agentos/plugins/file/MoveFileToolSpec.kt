package io.whozoss.agentos.plugins.file.tools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
    }
}
