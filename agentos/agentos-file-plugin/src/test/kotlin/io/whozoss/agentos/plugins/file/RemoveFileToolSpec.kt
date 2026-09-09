package io.whozoss.agentos.plugins.file.tools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.sdk.tool.ConfirmationMode
import io.whozoss.agentos.sdk.tool.ToolContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.writeText

class RemoveFileToolSpec : StringSpec() {
    private lateinit var tempDir: Path
    private val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())

    init {
        beforeEach {
            tempDir = Files.createTempDirectory("test")
        }

        afterEach {
            tempDir.toFile().deleteRecursively()
        }

        "removing existing file should succeed" {
            val tool = RemoveFileTool(tempDir)
            val file = tempDir.resolve("file.txt").also { it.writeText("content") }

            val result = tool.execute(RemoveFileTool.Input("file.txt"), ctx)

            result.success shouldBe true
            result.output shouldBe "File deleted successfully"
            file.exists() shouldBe false
        }

        "removing non-existent file should return not found message" {
            val tool = RemoveFileTool(tempDir)

            val result = tool.execute(RemoveFileTool.Input("nonexistent.txt"), ctx)

            result.success shouldBe false
            result.output shouldContain "Path does not exist"
        }

        "attempting to remove directory should reject" {
            val tool = RemoveFileTool(tempDir)
            Files.createDirectories(tempDir.resolve("dir"))

            val result = tool.execute(RemoveFileTool.Input("dir"), ctx)

            result.success shouldBe false
            result.output shouldContain "Cannot remove directories"
        }

        "removing file through valid symlink should remove the target" {
            val tool = RemoveFileTool(tempDir)
            val targetFile = tempDir.resolve("target.txt").also { it.writeText("content") }
            val linkFile = tempDir.resolve("link.txt")
            Files.createSymbolicLink(linkFile, targetFile)

            val result = tool.execute(RemoveFileTool.Input("link.txt"), ctx)

            result.success shouldBe true
            result.output shouldBe "File deleted successfully"
            linkFile.exists() shouldBe false
        }

        "nested file removal should work" {
            val tool = RemoveFileTool(tempDir)
            Files.createDirectories(tempDir.resolve("a/b/c"))
            val file = tempDir.resolve("a/b/c/file.txt").also { it.writeText("content") }

            val result = tool.execute(RemoveFileTool.Input("a/b/c/file.txt"), ctx)

            result.success shouldBe true
            result.output shouldBe "File deleted successfully"
            file.exists() shouldBe false
        }

        "path traversal attempt should error" {
            val tool = RemoveFileTool(tempDir)

            val result = tool.execute(RemoveFileTool.Input("../outside.txt"), ctx)

            result.success shouldBe false
            result.output shouldContain "path traversal not allowed"
        }

        // ─── WZ-31596 confirmation flow coverage ────────────────────────────────────────────

        "removing file via executeWithJson succeeds (parses JSON then delegates to execute)" {
            val tool = RemoveFileTool(tempDir)
            val file = tempDir.resolve("file.txt").also { it.writeText("content") }

            val result = tool.executeWithJson("""{"path":"file.txt"}""", ctx)

            result.success shouldBe true
            result.output shouldBe "File deleted successfully"
            file.exists() shouldBe false
        }

        "onRejected() returns the default cancellation message without touching the file" {
            val tool = RemoveFileTool(tempDir)
            val file = tempDir.resolve("file.txt").also { it.writeText("content") }

            val result = tool.onRejected()

            result shouldBe "Action cancelled."
            file.exists() shouldBe true
        }

        "getConfirmationMode returns EVERY_TIME (implicit consent never trusted)" {
            val tool = RemoveFileTool(tempDir)
            tool.getConfirmationMode() shouldBe ConfirmationMode.EVERY_TIME
        }
    }
}
