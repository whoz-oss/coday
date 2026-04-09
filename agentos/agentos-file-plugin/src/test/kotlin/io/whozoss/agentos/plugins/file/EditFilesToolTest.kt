package io.whozoss.agentos.plugins.file.tools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.plugins.file.tools.EditFilesTool
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

class EditFilesToolTest : StringSpec() {
    private lateinit var tempDir: Path

    init {
        beforeEach {
            tempDir = Files.createTempDirectory("test")
        }

        afterEach {
            tempDir.toFile().deleteRecursively()
        }

        "write new file should create with content" {
            val tool = EditFilesTool(tempDir)

            val result = tool.execute(
                EditFilesTool.Input(
                    edits = listOf(
                        mapOf("operation" to "write", "path" to "newfile.txt", "content" to "New content"),
                    ),
                ),
            )

            result shouldContain "File write success"
            tempDir.resolve("newfile.txt").exists() shouldBe true
            tempDir.resolve("newfile.txt").readText() shouldBe "New content"
        }

        "write overwrite file under 64KB should succeed" {
            val tool = EditFilesTool(tempDir)
            val file = tempDir.resolve("small.txt")
            file.writeText("Old content")

            val result = tool.execute(
                EditFilesTool.Input(
                    edits = listOf(
                        mapOf("operation" to "write", "path" to "small.txt", "content" to "New content"),
                    ),
                ),
            )

            result shouldContain "File write success"
            file.readText() shouldBe "New content"
        }

        "write overwrite file exceeding 64KB should reject with message" {
            val tool = EditFilesTool(tempDir)
            val file = tempDir.resolve("large.txt")
            val largeContent = "x".repeat(65 * 1024) // 65 KB
            file.writeText(largeContent)

            val result = tool.execute(
                EditFilesTool.Input(
                    edits = listOf(
                        mapOf("operation" to "write", "path" to "large.txt", "content" to "New content"),
                    ),
                ),
            )

            result shouldContain "not accepted"
            result shouldContain "64"
            file.readText() shouldBe largeContent // Should remain unchanged
        }

        "write new file with 200KB content should succeed" {
            val tool = EditFilesTool(tempDir)
            val largeContent = "y".repeat(200 * 1024) // 200 KB

            val result = tool.execute(
                EditFilesTool.Input(
                    edits = listOf(
                        mapOf("operation" to "write", "path" to "newlarge.txt", "content" to largeContent),
                    ),
                ),
            )

            result shouldContain "File write success"
            tempDir.resolve("newlarge.txt").readText() shouldBe largeContent
        }

        "write should create parent directories" {
            val tool = EditFilesTool(tempDir)

            val result = tool.execute(
                EditFilesTool.Input(
                    edits = listOf(
                        mapOf("operation" to "write", "path" to "a/b/c/file.txt", "content" to "nested"),
                    ),
                ),
            )

            result shouldContain "File write success"
            tempDir.resolve("a/b/c/file.txt").readText() shouldBe "nested"
        }

        "atomic write cleanup - tmp file should be deleted after success" {
            val tool = EditFilesTool(tempDir)

            tool.execute(
                EditFilesTool.Input(
                    edits = listOf(
                        mapOf("operation" to "write", "path" to "file.txt", "content" to "content"),
                    ),
                ),
            )

            val tmpFiles = Files.list(tempDir).filter { it.fileName.toString().contains(".tmp") }.toList()
            tmpFiles.size shouldBe 0
        }

        "patch with unique chunk >= 15 chars should apply replacement" {
            val tool = EditFilesTool(tempDir)
            val file = tempDir.resolve("file.txt")
            file.writeText("Hello world, this is a test")

            val result = tool.execute(
                EditFilesTool.Input(
                    edits = listOf(
                        mapOf(
                            "operation" to "patch",
                            "path" to "file.txt",
                            "replacements" to listOf(mapOf("oldPart" to "Hello world, this", "newPart" to "Goodbye universe, that")),
                        ),
                    ),
                ),
            )

            result shouldContain "successfully edited by chunks"
            file.readText() shouldBe "Goodbye universe, that is a test"
        }

        "patch with chunk not found should report error but still write file" {
            val tool = EditFilesTool(tempDir)
            val file = tempDir.resolve("file.txt")
            file.writeText("Hello world")

            val result = tool.execute(
                EditFilesTool.Input(
                    edits = listOf(
                        mapOf(
                            "operation" to "patch",
                            "path" to "file.txt",
                            "replacements" to listOf(mapOf("oldPart" to "Nonexistent chunk here", "newPart" to "replacement")),
                        ),
                    ),
                ),
            )

            result shouldContain "Chunks not found"
            result shouldContain "Nonexistent chunk here"
            file.readText() shouldBe "Hello world"
        }

        "patch with duplicate chunk should report error" {
            val tool = EditFilesTool(tempDir)
            val file = tempDir.resolve("file.txt")
            file.writeText("Hello world here, Hello world here")

            val result = tool.execute(
                EditFilesTool.Input(
                    edits = listOf(
                        mapOf(
                            "operation" to "patch",
                            "path" to "file.txt",
                            "replacements" to listOf(mapOf("oldPart" to "Hello world here", "newPart" to "Goodbye")),
                        ),
                    ),
                ),
            )

            result shouldContain "Duplicate chunks found"
            result shouldContain "Hello world here"
        }

        "patch with chunk too short < 15 chars should report error" {
            val tool = EditFilesTool(tempDir)
            val file = tempDir.resolve("file.txt")
            file.writeText("Hello world")

            val result = tool.execute(
                EditFilesTool.Input(
                    edits = listOf(
                        mapOf(
                            "operation" to "patch",
                            "path" to "file.txt",
                            "replacements" to listOf(mapOf("oldPart" to "short", "newPart" to "replacement")),
                        ),
                    ),
                ),
            )

            result shouldContain "Chunks too short"
            result shouldContain "short"
        }

        "batch edits on different files should all execute" {
            val tool = EditFilesTool(tempDir)

            val result = tool.execute(
                EditFilesTool.Input(
                    edits = listOf(
                        mapOf("operation" to "write", "path" to "file1.txt", "content" to "Content 1"),
                        mapOf("operation" to "write", "path" to "file2.txt", "content" to "Content 2"),
                    ),
                ),
            )

            result shouldContain "file1.txt: File write success"
            result shouldContain "file2.txt: File write success"
            tempDir.resolve("file1.txt").readText() shouldBe "Content 1"
            tempDir.resolve("file2.txt").readText() shouldBe "Content 2"
        }

        "batch edits - failure on one should not prevent others" {
            val tool = EditFilesTool(tempDir)
            val largeFile = tempDir.resolve("large.txt")
            largeFile.writeText("x".repeat(65 * 1024))

            val result = tool.execute(
                EditFilesTool.Input(
                    edits = listOf(
                        mapOf("operation" to "write", "path" to "success.txt", "content" to "Success"),
                        mapOf("operation" to "write", "path" to "large.txt", "content" to "Should fail"),
                        mapOf("operation" to "write", "path" to "success2.txt", "content" to "Success 2"),
                    ),
                ),
            )

            result shouldContain "success.txt: File write success"
            result shouldContain "large.txt:"
            result shouldContain "not accepted"
            result shouldContain "success2.txt: File write success"
            tempDir.resolve("success.txt").exists() shouldBe true
            tempDir.resolve("success2.txt").exists() shouldBe true
        }

        "patch on non-existent file should error" {
            val tool = EditFilesTool(tempDir)

            val result = tool.execute(
                EditFilesTool.Input(
                    edits = listOf(
                        mapOf(
                            "operation" to "patch",
                            "path" to "nonexistent.txt",
                            "replacements" to listOf(mapOf("oldPart" to "old text here 123", "newPart" to "new")),
                        ),
                    ),
                ),
            )

            result shouldContain "nonexistent.txt:"
            result shouldContain "Path does not exist"
        }

        "multiple patches in single edit should apply sequentially" {
            val tool = EditFilesTool(tempDir)
            val file = tempDir.resolve("file.txt")
            file.writeText("First line here\nSecond line here\nThird line here")

            val result = tool.execute(
                EditFilesTool.Input(
                    edits = listOf(
                        mapOf(
                            "operation" to "patch",
                            "path" to "file.txt",
                            "replacements" to listOf(
                                mapOf("oldPart" to "First line here", "newPart" to "1st line modified"),
                                mapOf("oldPart" to "Second line here", "newPart" to "2nd line modified"),
                            ),
                        ),
                    ),
                ),
            )

            result shouldContain "successfully edited by chunks"
            file.readText() shouldBe "1st line modified\n2nd line modified\nThird line here"
        }

        "empty edits list should return no edits provided" {
            val tool = EditFilesTool(tempDir)

            val result = tool.execute(EditFilesTool.Input(edits = emptyList()))

            result shouldContain "No edits provided"
        }

        "write should handle unicode content" {
            val tool = EditFilesTool(tempDir)

            val result = tool.execute(
                EditFilesTool.Input(
                    edits = listOf(
                        mapOf("operation" to "write", "path" to "unicode.txt", "content" to "Hello 世界 🌍"),
                    ),
                ),
            )

            result shouldContain "File write success"
            tempDir.resolve("unicode.txt").readText() shouldBe "Hello 世界 🌍"
        }

        "atomic write cleanup on move failure - tmp file should be cleaned up" {
            val tool = EditFilesTool(tempDir)

            val readOnlyDir = tempDir.resolve("readonly")
            Files.createDirectories(readOnlyDir)

            val isPosix = try {
                readOnlyDir.setPosixFilePermissions(PosixFilePermissions.fromString("r-xr-xr-x"))
                true
            } catch (e: UnsupportedOperationException) {
                false
            }

            if (isPosix) {
                val result = tool.execute(
                    EditFilesTool.Input(
                        edits = listOf(
                            mapOf("operation" to "write", "path" to "readonly/file.txt", "content" to "test content"),
                        ),
                    ),
                )

                result shouldContain "readonly/file.txt:"
                result shouldContain "Error"

                readOnlyDir.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))

                val tmpFiles = Files.list(readOnlyDir)
                    .filter { it.fileName.toString().contains(".tmp") }
                    .toList()
                tmpFiles.size shouldBe 0
            }
        }

        "deny-list write attempt to .env should be rejected" {
            val tool = EditFilesTool(tempDir)

            val result = tool.execute(
                EditFilesTool.Input(
                    edits = listOf(
                        mapOf("operation" to "write", "path" to ".env", "content" to "SECRET=value"),
                    ),
                ),
            )

            result shouldContain ".env:"
            result shouldContain "Access denied"
            tempDir.resolve(".env").exists() shouldBe false
        }

        "deny-list write attempt to .env.local should be rejected" {
            val tool = EditFilesTool(tempDir)

            val result = tool.execute(
                EditFilesTool.Input(
                    edits = listOf(
                        mapOf("operation" to "write", "path" to ".env.local", "content" to "SECRET=value"),
                    ),
                ),
            )

            result shouldContain ".env.local:"
            result shouldContain "Access denied"
            tempDir.resolve(".env.local").exists() shouldBe false
        }

        "deny-list patch attempt to .env should be rejected" {
            val tool = EditFilesTool(tempDir)
            tempDir.resolve(".env").writeText("OLD_SECRET=value")

            val result = tool.execute(
                EditFilesTool.Input(
                    edits = listOf(
                        mapOf(
                            "operation" to "patch",
                            "path" to ".env",
                            "replacements" to listOf(mapOf("oldPart" to "OLD_SECRET=value", "newPart" to "NEW_SECRET=changed")),
                        ),
                    ),
                ),
            )

            result shouldContain ".env:"
            result shouldContain "Access denied"
            tempDir.resolve(".env").readText() shouldBe "OLD_SECRET=value"
        }
    }
}
