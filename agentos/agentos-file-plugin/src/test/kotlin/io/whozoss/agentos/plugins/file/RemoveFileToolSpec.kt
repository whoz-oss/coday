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

            result shouldBe """"File deleted successfully""""
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

        "removing file through valid symlink should remove the target" {
            val tool = RemoveFileTool(tempDir)
            val targetFile = tempDir.resolve("target.txt").also { it.writeText("content") }
            val linkFile = tempDir.resolve("link.txt")
            Files.createSymbolicLink(linkFile, targetFile)

            val result = tool.execute(RemoveFileTool.Input("link.txt"), ctx)

            result shouldBe """"File deleted successfully""""
            linkFile.exists() shouldBe false
        }

        "nested file removal should work" {
            val tool = RemoveFileTool(tempDir)
            Files.createDirectories(tempDir.resolve("a/b/c"))
            val file = tempDir.resolve("a/b/c/file.txt").also { it.writeText("content") }

            val result = tool.execute(RemoveFileTool.Input("a/b/c/file.txt"), ctx)

            result shouldBe """"File deleted successfully""""
            file.exists() shouldBe false
        }

        "path traversal attempt should error" {
            val tool = RemoveFileTool(tempDir)

            val result = tool.execute(RemoveFileTool.Input("../outside.txt"), ctx)

            result shouldContain "path traversal not allowed"
        }
    }
}
