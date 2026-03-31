package io.whozoss.agentos.plugins.file

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.plugins.file.tools.SearchFilesTool
import io.whozoss.agentos.sdk.tool.ToolExecutionContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class SearchFilesToolTest : StringSpec() {
    private lateinit var tempDir: Path

    init {
        beforeEach {
            tempDir = Files.createTempDirectory("test")
        }

        afterEach {
            tempDir.toFile().deleteRecursively()
        }
        "search by fileName should find matching files" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = SearchFilesTool()
                val ctx = createContext(tempDir)
                tempDir.resolve("config.json").writeText("{}")
                tempDir.resolve("user-config.yaml").writeText("key: value")
                tempDir.resolve("other.txt").writeText("text")

                val result = tool.executeWithContext(
                    SearchFilesTool.Input(fileName = "config"),
                    ctx
                )

                result shouldContain "config.json"
                result shouldContain "user-config.yaml"
                result shouldNotContain "other.txt"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "search by fileContent should find matching files" {
            val tool = SearchFilesTool()
            val ctx = createContext(tempDir)
            tempDir.resolve("file1.txt").writeText("Hello world")
            tempDir.resolve("file2.txt").writeText("Goodbye world")
            tempDir.resolve("file3.txt").writeText("Nothing here")

            val result = tool.executeWithContext(
                SearchFilesTool.Input(fileContent = "world"),
                ctx
            )

            result shouldContain "file1.txt"
            result shouldContain "file2.txt"
            result shouldNotContain "file3.txt"
        }

        "search combined fileName and fileContent should return intersection" {
            val tool = SearchFilesTool()
            val ctx = createContext(tempDir)
            tempDir.resolve("config.json").writeText("{\"enabled\": true}")
            tempDir.resolve("config.yaml").writeText("disabled: false")
            tempDir.resolve("settings.json").writeText("{\"enabled\": false}")

            val result = tool.executeWithContext(
                SearchFilesTool.Input(fileName = "config", fileContent = "enabled"),
                ctx
            )

            result shouldContain "config.json"
            result shouldNotContain "config.yaml"
            result shouldNotContain "settings.json"
        }

        "no matching files should return no matching files found" {
            val tool = SearchFilesTool()
            val ctx = createContext(tempDir)
            tempDir.resolve("file.txt").writeText("content")

            val result = tool.executeWithContext(
                SearchFilesTool.Input(fileName = "nonexistent"),
                ctx
            )

            result shouldBe "No matching files found."
        }

        "smart return - small total size should return content with headers" {
            val tool = SearchFilesTool()
            val ctx = createContext(tempDir)
            tempDir.resolve("small1.txt").writeText("Content 1")
            tempDir.resolve("small2.txt").writeText("Content 2")

            val result = tool.executeWithContext(
                SearchFilesTool.Input(fileName = "small"),
                ctx
            )

            result shouldContain "=== project://small1.txt ==="
            result shouldContain "Content 1"
            result shouldContain "=== project://small2.txt ==="
            result shouldContain "Content 2"
        }

        "smart return - large total size should return paths only" {
            val tool = SearchFilesTool()
            val ctx = createContext(tempDir)
            val largeContent = "x".repeat(150 * 1024) // 150 KB
            tempDir.resolve("large1.txt").writeText(largeContent)
            tempDir.resolve("large2.txt").writeText(largeContent)

            val result = tool.executeWithContext(
                SearchFilesTool.Input(fileName = "large"),
                ctx
            )

            result shouldContain "project://large1.txt"
            result shouldContain "project://large2.txt"
            result shouldNotContain "==="
            result shouldNotContain "xxx"
        }

        "fileTypes filter should restrict by extension" {
            val tool = SearchFilesTool()
            val ctx = createContext(tempDir)
            tempDir.resolve("file1.ts").writeText("typescript")
            tempDir.resolve("file2.json").writeText("{\"name\": \"test\"}")
            tempDir.resolve("file3.txt").writeText("text")

            val result = tool.executeWithContext(
                SearchFilesTool.Input(fileContent = "e", fileTypes = listOf("ts", "json")),
                ctx
            )

            result shouldContain "file1.ts"
            result shouldContain "file2.json"
            result shouldNotContain "file3.txt"
        }

        "neither fileName nor fileContent should error" {
            val tool = SearchFilesTool()
            val ctx = createContext(tempDir)

            val result = tool.executeWithContext(
                SearchFilesTool.Input(),
                ctx
            )

            result shouldContain "At least one of fileName or fileContent must be provided"
        }

        "missing namespace project root should error" {
            val tool = SearchFilesTool()
            val ctx = ToolExecutionContext(
                namespaceId = UUID.randomUUID(),
                caseId = UUID.randomUUID(),
                fileRoots = emptyMap()
            )

            val result = tool.executeWithContext(
                SearchFilesTool.Input(fileName = "test"),
                ctx
            )

            result shouldContain "File tools require a configured namespace with project root"
        }

        "search in subdirectory using path parameter" {
            val tool = SearchFilesTool()
            val ctx = createContext(tempDir)
            Files.createDirectories(tempDir.resolve("subdir"))
            tempDir.resolve("subdir/file1.txt").writeText("content")
            tempDir.resolve("file2.txt").writeText("content")

            val result = tool.executeWithContext(
                SearchFilesTool.Input(fileName = "file", path = "project://subdir"),
                ctx
            )

            result shouldContain "subdir/file1.txt"
            result shouldNotContain "file2.txt"
        }

        "binary files in content search should be skipped or marked unreadable" {
            val tool = SearchFilesTool()
            val ctx = createContext(tempDir)
            tempDir.resolve("text.txt").writeText("searchable")
            tempDir.resolve("binary.bin").writeBytes(byteArrayOf(0x00, 0xFF.toByte()))

            val result = tool.executeWithContext(
                SearchFilesTool.Input(fileContent = "searchable"),
                ctx
            )

            result shouldContain "text.txt"
            result shouldNotContain "binary.bin"
        }

        "nested directory search should work" {
            val tool = SearchFilesTool()
            val ctx = createContext(tempDir)
            Files.createDirectories(tempDir.resolve("a/b/c"))
            tempDir.resolve("a/b/c/deep.txt").writeText("nested content")

            val result = tool.executeWithContext(
                SearchFilesTool.Input(fileName = "deep"),
                ctx
            )

            result shouldContain "a/b/c/deep.txt"
        }

        "case insensitive fileName search" {
            val tool = SearchFilesTool()
            val ctx = createContext(tempDir)
            tempDir.resolve("CONFIG.json").writeText("{}")
            tempDir.resolve("config.yaml").writeText("key: value")

            val result = tool.executeWithContext(
                SearchFilesTool.Input(fileName = "config"),
                ctx
            )

            result shouldContain "CONFIG.json"
            result shouldContain "config.yaml"
        }

        "case insensitive fileContent search" {
            val tool = SearchFilesTool()
            val ctx = createContext(tempDir)
            tempDir.resolve("file.txt").writeText("HELLO world")

            val result = tool.executeWithContext(
                SearchFilesTool.Input(fileContent = "hello"),
                ctx
            )

            result shouldContain "file.txt"
        }

        "pattern starting with - should fallback to NIO" {
            val tool = SearchFilesTool()
            val ctx = createContext(tempDir)
            tempDir.resolve("file1.txt").writeText("-flag content")
            tempDir.resolve("file2.txt").writeText("normal content")

            // Should not crash, should use NIO fallback
            val result = tool.executeWithContext(
                SearchFilesTool.Input(fileContent = "-flag"),
                ctx
            )

            result shouldContain "file1.txt"
            result shouldNotContain "file2.txt"
        }

        "pattern with null byte should fallback to NIO" {
            val tool = SearchFilesTool()
            val ctx = createContext(tempDir)
            tempDir.resolve("file1.txt").writeText("normal content")

            // Should not crash, should use NIO fallback (won't match anything)
            val result = tool.executeWithContext(
                SearchFilesTool.Input(fileContent = "test\u0000injection"),
                ctx
            )

            result shouldBe "No matching files found."
        }

        "pattern exceeding 1000 chars should fallback to NIO" {
            val tool = SearchFilesTool()
            val ctx = createContext(tempDir)
            val longPattern = "a".repeat(1001)
            tempDir.resolve("file1.txt").writeText(longPattern)

            // Should not crash, should use NIO fallback
            val result = tool.executeWithContext(
                SearchFilesTool.Input(fileContent = longPattern),
                ctx
            )

            result shouldContain "file1.txt"
        }
    }

    private fun createContext(tempDir: Path): ToolExecutionContext {
        return ToolExecutionContext(
            namespaceId = UUID.randomUUID(),
            caseId = UUID.randomUUID(),
            fileRoots = mapOf("project" to tempDir)
        )
    }
}
