package io.whozoss.agentos.plugins.file

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.plugins.file.tools.ReadFileTool
import java.nio.file.Files
import kotlin.io.path.createFile
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class ReadFileToolTest : StringSpec() {
    init {
        "reading UTF-8 text file should return content correctly" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ReadFileTool(tempDir)
                val file = tempDir.resolve("test.txt")
                file.writeText("Hello, World!\nLine 2\nLine 3")

                val result = tool.execute(ReadFileTool.Input("project://test.txt"))

                result shouldBe "Hello, World!\nLine 2\nLine 3"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "reading non-existent file should return error message" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ReadFileTool(tempDir)

                val result = tool.execute(ReadFileTool.Input("project://nonexistent.txt"))

                result shouldContain "Path does not exist"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "reading empty file should return empty string" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ReadFileTool(tempDir)
                val file = tempDir.resolve("empty.txt").createFile()

                val result = tool.execute(ReadFileTool.Input("project://empty.txt"))

                result shouldBe ""
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "reading binary file should return binary or unreadable message" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ReadFileTool(tempDir)
                val file = tempDir.resolve("binary.bin")
                file.writeBytes(byteArrayOf(0x00, 0xFF.toByte(), 0xFE.toByte(), 0x00, 0x01))

                val result = tool.execute(ReadFileTool.Input("project://binary.bin"))

                result shouldBe "[binary or unreadable file]"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "reading file exceeding 10MB should return error" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ReadFileTool(tempDir)
                val file = tempDir.resolve("large.txt")
                val largeContent = "x".repeat(11 * 1024 * 1024) // 11 MB
                file.writeText(largeContent)

                val result = tool.execute(ReadFileTool.Input("project://large.txt"))

                result shouldContain "exceeds maximum size"
                result shouldContain "10"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "reading file with unicode content should work" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ReadFileTool(tempDir)
                val file = tempDir.resolve("unicode.txt")
                file.writeText("Hello 世界 🌍 émoji")

                val result = tool.execute(ReadFileTool.Input("project://unicode.txt"))

                result shouldBe "Hello 世界 🌍 émoji"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "reading file through valid symlink should work" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ReadFileTool(tempDir)
                val targetFile = tempDir.resolve("target.txt")
                targetFile.writeText("target content")
                val linkFile = tempDir.resolve("link.txt")
                Files.createSymbolicLink(linkFile, targetFile)

                val result = tool.execute(ReadFileTool.Input("project://link.txt"))

                result shouldBe "target content"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "multi-line text file should preserve line breaks" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ReadFileTool(tempDir)
                val file = tempDir.resolve("multi.txt")
                val content = "Line 1\nLine 2\r\nLine 3\n\nLine 5"
                file.writeText(content)

                val result = tool.execute(ReadFileTool.Input("project://multi.txt"))

                result shouldBe content
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    }
}
