package io.whozoss.agentos.plugins.file

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.plugins.file.tools.ReadFileTool
import java.nio.file.Files
import kotlin.io.path.createFile
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class ReadFileToolSpec : StringSpec() {
    init {
        "reading UTF-8 text file should return content correctly" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ReadFileTool(tempDir)
                val file = tempDir.resolve("test.txt")
                file.writeText("Hello, World!\nLine 2\nLine 3")

                val result = tool.execute(ReadFileTool.Input("test.txt"))

                result shouldBe "Hello, World!\nLine 2\nLine 3"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "reading non-existent file should return error message" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ReadFileTool(tempDir)

                val result = tool.execute(ReadFileTool.Input("nonexistent.txt"))

                result shouldContain "Path does not exist"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "reading empty file should return empty string" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ReadFileTool(tempDir)
                tempDir.resolve("empty.txt").createFile()

                val result = tool.execute(ReadFileTool.Input("empty.txt"))

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

                val result = tool.execute(ReadFileTool.Input("binary.bin"))

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

                val result = tool.execute(ReadFileTool.Input("large.txt"))

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

                val result = tool.execute(ReadFileTool.Input("unicode.txt"))

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

                val result = tool.execute(ReadFileTool.Input("link.txt"))

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

                val result = tool.execute(ReadFileTool.Input("multi.txt"))

                result shouldBe content
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "should reject file exceeding custom readMaxSizeBytes" {
            val tempDir = Files.createTempDirectory("test")
            try {
                // Create ReadFileTool with custom 1 KB limit
                val tool = ReadFileTool(tempDir, readMaxSizeBytes = 1024)
                val file = tempDir.resolve("twoKb.txt")
                val content = "x".repeat(2 * 1024) // 2 KB
                file.writeText(content)

                val result = tool.execute(ReadFileTool.Input("twoKb.txt"))

                result shouldContain "exceeds maximum size"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "should use default readMaxSizeBytes when not specified" {
            val tempDir = Files.createTempDirectory("test")
            try {
                // Default is 10 MB, so a 5 MB file should work
                val tool = ReadFileTool(tempDir)
                val file = tempDir.resolve("fiveMb.txt")
                val content = "x".repeat(5 * 1024 * 1024) // 5 MB
                file.writeText(content)

                val result = tool.execute(ReadFileTool.Input("fiveMb.txt"))

                // Should succeed and return the content (we'll just check it's not an error)
                result shouldBe content
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    }
}
