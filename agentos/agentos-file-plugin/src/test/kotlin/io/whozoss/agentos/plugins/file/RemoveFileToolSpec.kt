package io.whozoss.agentos.plugins.file.tools

import io.kotest.assertions.throwables.shouldThrow
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
 * RemoveFileTool now opts in to the WZ-31596 confirmation flow. The tool's `execute`
 * path is deliberately a guard that refuses to apply the deletion (the orchestrator is
 * expected to route through `getConfirmationPayload` → user prompt → `executeWithConfirmation`).
 *
 * These specs exercise the same scenarios as before, but against the confirmation flow:
 * `getConfirmationPayload` performs all validation (no side-effect) and
 * `executeWithConfirmation` does the actual deletion.
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

        "removing existing file via confirmation flow succeeds" {
            val tool = RemoveFileTool(tempDir)
            val file = tempDir.resolve("file.txt").also { it.writeText("content") }

            val payload = tool.getConfirmationPayload(RemoveFileTool.Input("file.txt"), ctx)
            val result = tool.executeWithConfirmation(payload, ctx)

            result shouldContain "deleted successfully"
            file.exists() shouldBe false
        }

        "removing non-existent file is rejected at getConfirmationPayload (validation step)" {
            val tool = RemoveFileTool(tempDir)
            val ex = shouldThrow<IllegalArgumentException> {
                tool.getConfirmationPayload(RemoveFileTool.Input("nonexistent.txt"), ctx)
            }
            ex.message!! shouldContain "Path does not exist"
        }

        "attempting to remove directory is rejected at getConfirmationPayload" {
            val tool = RemoveFileTool(tempDir)
            Files.createDirectories(tempDir.resolve("dir"))

            val ex = shouldThrow<IllegalArgumentException> {
                tool.getConfirmationPayload(RemoveFileTool.Input("dir"), ctx)
            }
            ex.message!! shouldContain "Cannot remove directories"
        }

        "removing file through valid symlink resolves the target via the confirmation flow" {
            val tool = RemoveFileTool(tempDir)
            val targetFile = tempDir.resolve("target.txt").also { it.writeText("content") }
            val linkFile = tempDir.resolve("link.txt")
            Files.createSymbolicLink(linkFile, targetFile)

            val payload = tool.getConfirmationPayload(RemoveFileTool.Input("link.txt"), ctx)
            val result = tool.executeWithConfirmation(payload, ctx)

            result shouldContain "deleted successfully"
            linkFile.exists() shouldBe false
        }

        "nested file removal works via the confirmation flow" {
            val tool = RemoveFileTool(tempDir)
            Files.createDirectories(tempDir.resolve("a/b/c"))
            val file = tempDir.resolve("a/b/c/file.txt").also { it.writeText("content") }

            val payload = tool.getConfirmationPayload(RemoveFileTool.Input("a/b/c/file.txt"), ctx)
            val result = tool.executeWithConfirmation(payload, ctx)

            result shouldContain "deleted successfully"
            file.exists() shouldBe false
        }

        "path traversal attempt is rejected at getConfirmationPayload" {
            val tool = RemoveFileTool(tempDir)

            val ex = shouldThrow<IllegalArgumentException> {
                tool.getConfirmationPayload(RemoveFileTool.Input("../outside.txt"), ctx)
            }
            ex.message!! shouldContain "path traversal not allowed"
        }

        "onRejected() returns a non-execution message without touching the file" {
            val tool = RemoveFileTool(tempDir)
            val file = tempDir.resolve("file.txt").also { it.writeText("content") }

            val payload = tool.getConfirmationPayload(RemoveFileTool.Input("file.txt"), ctx)
            val result = tool.onRejected(payload, "non, annule", ctx)

            result shouldContain "was not performed"
            file.exists() shouldBe true
        }

        "supportsConfirmation is true and requiresConfirmation depends on path presence" {
            val tool = RemoveFileTool(tempDir)
            tool.supportsConfirmation shouldBe true
            tool.requiresConfirmation(RemoveFileTool.Input("file.txt"), ctx) shouldBe true
            tool.requiresConfirmation(RemoveFileTool.Input(""), ctx) shouldBe false
            tool.requiresConfirmation(null, ctx) shouldBe false
        }
    })
