package io.whozoss.agentos.plugins.file.tools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.sdk.tool.ToolContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * RemoveFileTool exposes two paths:
 *  - `execute(input, ctx)` — direct, used by AgentSimple (Spring AI native tool_use)
 *  - `executeWithConfirmation(input, ctx)` — used by AgentAdvanced post-confirmation;
 *    default delegates to `execute` so the deletion logic is shared.
 *
 * The orchestrator gating is asserted in `AgentAdvancedSpec` — here we only validate the
 * tool's own behaviour (validation, error handling, requiresConfirmation flag).
 */
class RemoveFileToolSpec :
    StringSpec({

        lateinit var tempDir: Path
        val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())

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

            result shouldBe "File deleted successfully"
            file.exists() shouldBe false
        }

        "removing non-existent file should return not found message" {
            val tool = RemoveFileTool(tempDir)

            val result = tool.execute(RemoveFileTool.Input("nonexistent.txt"), ctx)

            result shouldContain "Path does not exist"
        }

        "attempting to remove directory should reject" {
            val tool = RemoveFileTool(tempDir)
            Files.createDirectories(tempDir.resolve("dir"))

            val result = tool.execute(RemoveFileTool.Input("dir"), ctx)

            result shouldContain "Cannot remove directories"
        }

        "removing file via executeWithConfirmation succeeds (default delegates to execute)" {
            val tool = RemoveFileTool(tempDir)
            val file = tempDir.resolve("file.txt").also { it.writeText("content") }

            val result = tool.executeWithConfirmation(RemoveFileTool.Input("file.txt"), ctx)

            result shouldBe "File deleted successfully"
            file.exists() shouldBe false
        }

        "removing nested file via executeWithConfirmation works" {
            val tool = RemoveFileTool(tempDir)
            Files.createDirectories(tempDir.resolve("a/b/c"))
            val file = tempDir.resolve("a/b/c/file.txt").also { it.writeText("content") }

            val result = tool.executeWithConfirmation(RemoveFileTool.Input("a/b/c/file.txt"), ctx)

            result shouldBe "File deleted successfully"
            file.exists() shouldBe false
        }

        "removing file through valid symlink resolves the target" {
            val tool = RemoveFileTool(tempDir)
            val targetFile = tempDir.resolve("target.txt").also { it.writeText("content") }
            val linkFile = tempDir.resolve("link.txt")
            Files.createSymbolicLink(linkFile, targetFile)

            val result = tool.executeWithConfirmation(RemoveFileTool.Input("link.txt"), ctx)

            result shouldBe "File deleted successfully"
            linkFile.exists() shouldBe false
        }

        "path traversal attempt is rejected" {
            val tool = RemoveFileTool(tempDir)

            val result = tool.execute(RemoveFileTool.Input("../outside.txt"), ctx)

            result shouldContain "path traversal not allowed"
        }

        "onRejected() returns the default cancellation message without touching the file" {
            val tool = RemoveFileTool(tempDir)
            val file = tempDir.resolve("file.txt").also { it.writeText("content") }

            val result = tool.onRejected()

            result shouldBe "Action cancelled."
            file.exists() shouldBe true
        }

        "requiresConfirmation depends on path presence; bypassImplicitConsent is true" {
            val tool = RemoveFileTool(tempDir)
            tool.bypassImplicitConsent shouldBe true
            tool.requiresConfirmation(RemoveFileTool.Input("file.txt"), ctx) shouldBe true
            tool.requiresConfirmation(RemoveFileTool.Input(""), ctx) shouldBe false
            tool.requiresConfirmation(null, ctx) shouldBe false
        }
    })
