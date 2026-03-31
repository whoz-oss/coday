package io.whozoss.agentos.plugins.file.tools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.plugins.file.tools.RemoveFileTool
import io.whozoss.agentos.sdk.tool.ToolExecutionContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
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
            val tool = RemoveFileTool()
            val ctx = createContext()
            val file = tempDir.resolve("file.txt").also { it.writeText("content") }

            val result = tool.executeWithContext(RemoveFileTool.Input("project://file.txt"), ctx)

            result shouldBe "File deleted successfully"
            file.exists() shouldBe false
        }

        "removing non-existent file should return not found message" {
            val tool = RemoveFileTool()
            val ctx = createContext()

            val result = tool.executeWithContext(RemoveFileTool.Input("project://nonexistent.txt"), ctx)

            result shouldContain "Path does not exist"
        }

        "attempting to remove directory should reject" {
            val tool = RemoveFileTool()
            val ctx = createContext()
            Files.createDirectories(tempDir.resolve("dir"))

            val result = tool.executeWithContext(RemoveFileTool.Input("project://dir"), ctx)

            result shouldContain "Cannot remove directories"
        }

        "readOnly mode should reject removal" {
            val tool = RemoveFileTool()
            val ctx = createContextReadOnly()
            tempDir.resolve("file.txt").createFile()

            val result = tool.executeWithContext(RemoveFileTool.Input("project://file.txt"), ctx)

            result shouldContain "Cannot modify files in read-only mode"
        }

        "missing namespace project root should error" {
            val tool = RemoveFileTool()
            val ctx = ToolExecutionContext(
                namespaceId = UUID.randomUUID(),
                caseId = UUID.randomUUID(),
                fileRoots = emptyMap()
            )

            val result = tool.executeWithContext(RemoveFileTool.Input("project://file.txt"), ctx)

            result shouldContain "File tools require a configured namespace with project root"
        }

        "removing file through valid symlink should remove the target" {
            val tool = RemoveFileTool()
            val ctx = createContext()
            val targetFile = tempDir.resolve("target.txt").also { it.writeText("content") }
            val linkFile = tempDir.resolve("link.txt")
            Files.createSymbolicLink(linkFile, targetFile)

            val result = tool.executeWithContext(RemoveFileTool.Input("project://link.txt"), ctx)

            result shouldBe "File deleted successfully"
            // The symlink resolution means we delete the target
            linkFile.exists() shouldBe false
        }

        "nested file removal should work" {
            val tool = RemoveFileTool()
            val ctx = createContext()
            Files.createDirectories(tempDir.resolve("a/b/c"))
            val file = tempDir.resolve("a/b/c/file.txt").also { it.writeText("content") }

            val result = tool.executeWithContext(RemoveFileTool.Input("project://a/b/c/file.txt"), ctx)

            result shouldBe "File deleted successfully"
            file.exists() shouldBe false
        }

        "invalid path should error" {
            val tool = RemoveFileTool()
            val ctx = createContext()

            val result = tool.executeWithContext(RemoveFileTool.Input("invalid-path"), ctx)

            result shouldContain "must start with"
        }
    }

    private fun createContext(): ToolExecutionContext {
        return ToolExecutionContext(
            namespaceId = UUID.randomUUID(),
            caseId = UUID.randomUUID(),
            fileRoots = mapOf("project" to tempDir)
        )
    }

    private fun createContextReadOnly(): ToolExecutionContext {
        return ToolExecutionContext(
            namespaceId = UUID.randomUUID(),
            caseId = UUID.randomUUID(),
            fileRoots = mapOf("project" to tempDir),
            properties = mapOf("readOnly" to "true")
        )
    }
}
