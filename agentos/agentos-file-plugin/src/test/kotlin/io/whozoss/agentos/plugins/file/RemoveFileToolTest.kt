package io.whozoss.agentos.plugins.file.tools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.plugins.file.tools.RemoveFileTool
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.writeText

class RemoveFileToolTest : StringSpec() {
    private lateinit var tempDir: Path

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

            val result = tool.execute(RemoveFileTool.Input("project://file.txt"))

            result shouldBe "File deleted successfully"
            file.exists() shouldBe false
        }

        "removing non-existent file should return not found message" {
            val tool = RemoveFileTool(tempDir)

            val result = tool.execute(RemoveFileTool.Input("project://nonexistent.txt"))

            result shouldContain "Path does not exist"
        }

        "attempting to remove directory should reject" {
            val tool = RemoveFileTool(tempDir)
            Files.createDirectories(tempDir.resolve("dir"))

            val result = tool.execute(RemoveFileTool.Input("project://dir"))

            result shouldContain "Cannot remove directories"
        }

        "readOnly mode should reject removal" {
            val tool = RemoveFileTool(tempDir, readOnly = true)
            tempDir.resolve("file.txt").createFile()

            val result = tool.execute(RemoveFileTool.Input("project://file.txt"))

            result shouldContain "Cannot modify files in read-only mode"
        }

        "removing file through valid symlink should remove the target" {
            val tool = RemoveFileTool(tempDir)
            val targetFile = tempDir.resolve("target.txt").also { it.writeText("content") }
            val linkFile = tempDir.resolve("link.txt")
            Files.createSymbolicLink(linkFile, targetFile)

            val result = tool.execute(RemoveFileTool.Input("project://link.txt"))

            result shouldBe "File deleted successfully"
            // The symlink resolution means we delete the target
            linkFile.exists() shouldBe false
        }

        "nested file removal should work" {
            val tool = RemoveFileTool(tempDir)
            Files.createDirectories(tempDir.resolve("a/b/c"))
            val file = tempDir.resolve("a/b/c/file.txt").also { it.writeText("content") }

            val result = tool.execute(RemoveFileTool.Input("project://a/b/c/file.txt"))

            result shouldBe "File deleted successfully"
            file.exists() shouldBe false
        }

        "invalid path should error" {
            val tool = RemoveFileTool(tempDir)

            val result = tool.execute(RemoveFileTool.Input("invalid-path"))

            result shouldContain "must start with"
        }
    }
}
