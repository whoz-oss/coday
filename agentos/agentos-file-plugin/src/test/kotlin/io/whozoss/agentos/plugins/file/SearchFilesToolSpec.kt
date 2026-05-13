package io.whozoss.agentos.plugins.file

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.plugins.file.tools.SearchFilesTool
import io.whozoss.agentos.sdk.tool.ToolContext
import java.nio.file.Files
import java.util.UUID
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class SearchFilesToolSpec : StringSpec() {
    private lateinit var tempDir: Path
    private val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())

    init {
        beforeEach {
            tempDir = Files.createTempDirectory("test")
        }

        afterEach {
            tempDir.toFile().deleteRecursively()
        }

        "search by fileName should find matching files" {
            val tool = SearchFilesTool(tempDir)
            tempDir.resolve("config.json").writeText("{}")
            tempDir.resolve("user-config.yaml").writeText("key: value")
            tempDir.resolve("other.txt").writeText("text")

            val result = tool.execute(SearchFilesTool.Input(fileName = "config"), ctx)

            result shouldContain "config.json"
            result shouldContain "user-config.yaml"
            result shouldNotContain "other.txt"
        }

        "search by fileContent should find matching files" {
            val tool = SearchFilesTool(tempDir)
            tempDir.resolve("file1.txt").writeText("Hello world")
            tempDir.resolve("file2.txt").writeText("Goodbye world")
            tempDir.resolve("file3.txt").writeText("Nothing here")

            val result = tool.execute(SearchFilesTool.Input(fileContent = "world"), ctx)

            result shouldContain "file1.txt"
            result shouldContain "file2.txt"
            result shouldNotContain "file3.txt"
        }

        "search combined fileName and fileContent should return intersection" {
            val tool = SearchFilesTool(tempDir)
            tempDir.resolve("config.json").writeText("{\"enabled\": true}")
            tempDir.resolve("config.yaml").writeText("disabled: false")
            tempDir.resolve("settings.json").writeText("{\"enabled\": false}")

            val result = tool.execute(SearchFilesTool.Input(fileName = "config", fileContent = "enabled"), ctx)

            result shouldContain "config.json"
            result shouldNotContain "config.yaml"
            result shouldNotContain "settings.json"
        }

        "no matching files should return no matching files found" {
            val tool = SearchFilesTool(tempDir)
            tempDir.resolve("file.txt").writeText("content")

            val result = tool.execute(SearchFilesTool.Input(fileName = "nonexistent"), ctx)

            result shouldBe "No matching files found."
        }

        "smart return - small total size should return content with headers" {
            val tool = SearchFilesTool(tempDir)
            tempDir.resolve("small1.txt").writeText("Content 1")
            tempDir.resolve("small2.txt").writeText("Content 2")

            val result = tool.execute(SearchFilesTool.Input(fileName = "small"), ctx)

            result shouldContain "=== small1.txt ==="
            result shouldContain "Content 1"
            result shouldContain "=== small2.txt ==="
            result shouldContain "Content 2"
        }

        "smart return - large total size should return paths only" {
            val tool = SearchFilesTool(tempDir)
            val largeContent = "x".repeat(150 * 1024) // 150 KB
            tempDir.resolve("large1.txt").writeText(largeContent)
            tempDir.resolve("large2.txt").writeText(largeContent)

            val result = tool.execute(SearchFilesTool.Input(fileName = "large"), ctx)

            result shouldContain "large1.txt"
            result shouldContain "large2.txt"
            result shouldNotContain "==="
            result shouldNotContain "xxx"
        }

        "fileTypes filter should restrict by extension" {
            val tool = SearchFilesTool(tempDir)
            tempDir.resolve("file1.ts").writeText("typescript")
            tempDir.resolve("file2.json").writeText("{\"name\": \"test\"}")
            tempDir.resolve("file3.txt").writeText("text")

            val result = tool.execute(SearchFilesTool.Input(fileContent = "e", fileTypes = listOf("ts", "json")), ctx)

            result shouldContain "file1.ts"
            result shouldContain "file2.json"
            result shouldNotContain "file3.txt"
        }

        "neither fileName nor fileContent should error" {
            val tool = SearchFilesTool(tempDir)

            val result = tool.execute(SearchFilesTool.Input(), ctx)

            result shouldContain "At least one of fileName or fileContent must be provided"
        }

        "search in subdirectory using path parameter" {
            val tool = SearchFilesTool(tempDir)
            Files.createDirectories(tempDir.resolve("subdir"))
            tempDir.resolve("subdir/file1.txt").writeText("content")
            tempDir.resolve("file2.txt").writeText("content")

            val result = tool.execute(SearchFilesTool.Input(fileName = "file", path = "subdir"), ctx)

            result shouldContain "subdir/file1.txt"
            result shouldNotContain "file2.txt"
        }

        "binary files in content search should be skipped or marked unreadable" {
            val tool = SearchFilesTool(tempDir)
            tempDir.resolve("text.txt").writeText("searchable")
            tempDir.resolve("binary.bin").writeBytes(byteArrayOf(0x00, 0xFF.toByte()))

            val result = tool.execute(SearchFilesTool.Input(fileContent = "searchable"), ctx)

            result shouldContain "text.txt"
            result shouldNotContain "binary.bin"
        }

        "nested directory search should work" {
            val tool = SearchFilesTool(tempDir)
            Files.createDirectories(tempDir.resolve("a/b/c"))
            tempDir.resolve("a/b/c/deep.txt").writeText("nested content")

            val result = tool.execute(SearchFilesTool.Input(fileName = "deep"), ctx)

            result shouldContain "a/b/c/deep.txt"
        }

        "case insensitive fileName search" {
            val tool = SearchFilesTool(tempDir)
            tempDir.resolve("CONFIG.json").writeText("{}")
            tempDir.resolve("config.yaml").writeText("key: value")

            val result = tool.execute(SearchFilesTool.Input(fileName = "config"), ctx)

            result shouldContain "CONFIG.json"
            result shouldContain "config.yaml"
        }

        "case insensitive fileContent search" {
            val tool = SearchFilesTool(tempDir)
            tempDir.resolve("file.txt").writeText("HELLO world")

            val result = tool.execute(SearchFilesTool.Input(fileContent = "hello"), ctx)

            result shouldContain "file.txt"
        }

        "pattern starting with - should fallback to NIO" {
            val tool = SearchFilesTool(tempDir)
            tempDir.resolve("file1.txt").writeText("-flag content")
            tempDir.resolve("file2.txt").writeText("normal content")

            val result = tool.execute(SearchFilesTool.Input(fileContent = "-flag"), ctx)

            result shouldContain "file1.txt"
            result shouldNotContain "file2.txt"
        }

        "pattern with null byte should fallback to NIO" {
            val tool = SearchFilesTool(tempDir)
            tempDir.resolve("file1.txt").writeText("normal content")

            val result = tool.execute(SearchFilesTool.Input(fileContent = "test\u0000injection"), ctx)

            result shouldBe "No matching files found."
        }

        "pattern exceeding 1000 chars should fallback to NIO" {
            val tool = SearchFilesTool(tempDir)
            val longPattern = "a".repeat(1001)
            tempDir.resolve("file1.txt").writeText(longPattern)

            val result = tool.execute(SearchFilesTool.Input(fileContent = longPattern), ctx)

            result shouldContain "file1.txt"
        }

        "paths in results should not expose root directory" {
            val tool = SearchFilesTool(tempDir)
            tempDir.resolve("secret.txt").writeText("some content")

            val result = tool.execute(SearchFilesTool.Input(fileName = "secret"), ctx)

            // Result should be a relative path, not an absolute path containing the temp dir
            result shouldNotContain tempDir.toString()
            result shouldContain "secret.txt"
        }

        "ripgrep --iglob should match fileName case-insensitively with fileContent" {
            val tool = SearchFilesTool(tempDir)
            tempDir.resolve("TestFile.txt").writeText("searchable content")
            tempDir.resolve("testfile.md").writeText("searchable content")
            tempDir.resolve("TESTFILE.json").writeText("searchable content")

            val result = tool.execute(SearchFilesTool.Input(fileName = "testfile", fileContent = "searchable"), ctx)

            // All three files should match regardless of case
            result shouldContain "TestFile.txt"
            result shouldContain "testfile.md"
            result shouldContain "TESTFILE.json"
        }

        "search should not expose denied files like .env" {
            val tool = SearchFilesTool(tempDir)
            tempDir.resolve(".env").writeText("SECRET_KEY=supersecret")
            tempDir.resolve("config.env").writeText("PUBLIC_CONFIG=ok")

            // Search for .ENV (uppercase) should not return .env contents
            val result = tool.execute(SearchFilesTool.Input(fileName = ".ENV"), ctx)

            // .env file should be filtered out due to deny pattern
            result shouldNotContain "SECRET_KEY"
            result shouldNotContain "supersecret"
            // Note: Can't use "shouldNotContain .env" because result contains "config.env"
            // Instead, verify that the .env file name doesn't appear as a standalone match
            val lines = result.split("\n")
            lines.none { it.trim() == ".env" || it.contains("=== .env ===") } shouldBe true

            // config.env should be allowed
            result shouldContain "config.env"
        }

        "should deny extra patterns passed via constructor" {
            // Construct SearchFilesTool with custom denyPatterns
            val customDenyPatterns = SensitiveFilePatterns.DEFAULT_PATTERNS + listOf("*.custom")
            val tool = SearchFilesTool(tempDir, denyPatterns = customDenyPatterns)

            tempDir.resolve("allowed.txt").writeText("public data")
            tempDir.resolve("secret.custom").writeText("secret data")

            val result = tool.execute(SearchFilesTool.Input(fileContent = "data"), ctx)

            // *.custom file should be filtered out
            result shouldNotContain "secret.custom"
            // allowed.txt should appear
            result shouldContain "allowed.txt"
        }
    }
}
