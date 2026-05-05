package io.whozoss.agentos.plugins.file.tools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.plugins.file.tools.EditFilesTool.Input
import io.whozoss.agentos.plugins.file.tools.EditFilesTool.PatchEdit
import io.whozoss.agentos.plugins.file.tools.EditFilesTool.Replacement
import io.whozoss.agentos.plugins.file.tools.EditFilesTool.WriteEdit
import io.whozoss.agentos.sdk.tool.ToolContext
import java.nio.file.Files
import java.util.UUID
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

class EditFilesToolSpec : StringSpec() {
    private lateinit var tempDir: Path
    private val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())

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
                Input(
                    edits = listOf(
                        WriteEdit(path = "newfile.txt", content = "New content"),
                    ),
                ),
                ctx,
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
                Input(
                    edits = listOf(
                        WriteEdit(path = "small.txt", content = "New content"),
                    ),
                ),
                ctx,
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
                Input(
                    edits = listOf(
                        WriteEdit(path = "large.txt", content = "New content"),
                    ),
                ),
                ctx,
            )

            result shouldContain "not accepted"
            result shouldContain "64"
            file.readText() shouldBe largeContent // Should remain unchanged
        }

        "write new file with 200KB content should succeed" {
            val tool = EditFilesTool(tempDir)
            val largeContent = "y".repeat(200 * 1024) // 200 KB

            val result = tool.execute(
                Input(
                    edits = listOf(
                        WriteEdit(path = "newlarge.txt", content = largeContent),
                    ),
                ),
                ctx,
            )

            result shouldContain "File write success"
            tempDir.resolve("newlarge.txt").readText() shouldBe largeContent
        }

        "write should create parent directories" {
            val tool = EditFilesTool(tempDir)

            val result = tool.execute(
                Input(
                    edits = listOf(
                        WriteEdit(path = "a/b/c/file.txt", content = "nested"),
                    ),
                ),
                ctx,
            )

            result shouldContain "File write success"
            tempDir.resolve("a/b/c/file.txt").readText() shouldBe "nested"
        }

        "atomic write cleanup - tmp file should be deleted after success" {
            val tool = EditFilesTool(tempDir)

            tool.execute(
                Input(
                    edits = listOf(
                        WriteEdit(path = "file.txt", content = "content"),
                    ),
                ),
                ctx,
            )

            val tmpFiles = Files.list(tempDir).filter { it.fileName.toString().contains(".tmp") }.toList()
            tmpFiles.size shouldBe 0
        }

        "patch with unique chunk >= 15 chars should apply replacement" {
            val tool = EditFilesTool(tempDir)
            val file = tempDir.resolve("file.txt")
            file.writeText("Hello world, this is a test")

            val result = tool.execute(
                Input(
                    edits = listOf(
                        PatchEdit(
                            path = "file.txt",
                            replacements = listOf(Replacement(oldPart = "Hello world, this", newPart = "Goodbye universe, that")),
                        ),
                    ),
                ),
                ctx,
            )

            result shouldContain "successfully edited by chunks"
            file.readText() shouldBe "Goodbye universe, that is a test"
        }

        "patch with chunk not found should report error but still write file" {
            val tool = EditFilesTool(tempDir)
            val file = tempDir.resolve("file.txt")
            file.writeText("Hello world")

            val result = tool.execute(
                Input(
                    edits = listOf(
                        PatchEdit(
                            path = "file.txt",
                            replacements = listOf(Replacement(oldPart = "Nonexistent chunk here", newPart = "replacement")),
                        ),
                    ),
                ),
                ctx,
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
                Input(
                    edits = listOf(
                        PatchEdit(
                            path = "file.txt",
                            replacements = listOf(Replacement(oldPart = "Hello world here", newPart = "Goodbye")),
                        ),
                    ),
                ),
                ctx,
            )

            result shouldContain "Duplicate chunks found"
            result shouldContain "Hello world here"
        }

        "patch with chunk too short < 15 chars should report error" {
            val tool = EditFilesTool(tempDir)
            val file = tempDir.resolve("file.txt")
            file.writeText("Hello world")

            val result = tool.execute(
                Input(
                    edits = listOf(
                        PatchEdit(
                            path = "file.txt",
                            replacements = listOf(Replacement(oldPart = "short", newPart = "replacement")),
                        ),
                    ),
                ),
                ctx,
            )

            result shouldContain "Chunks too short"
            result shouldContain "short"
        }

        "batch edits on different files should all execute" {
            val tool = EditFilesTool(tempDir)

            val result = tool.execute(
                Input(
                    edits = listOf(
                        WriteEdit(path = "file1.txt", content = "Content 1"),
                        WriteEdit(path = "file2.txt", content = "Content 2"),
                    ),
                ),
                ctx,
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
                Input(
                    edits = listOf(
                        WriteEdit(path = "success.txt", content = "Success"),
                        WriteEdit(path = "large.txt", content = "Should fail"),
                        WriteEdit(path = "success2.txt", content = "Success 2"),
                    ),
                ),
                ctx,
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
                Input(
                    edits = listOf(
                        PatchEdit(
                            path = "nonexistent.txt",
                            replacements = listOf(Replacement(oldPart = "old text here 123", newPart = "new")),
                        ),
                    ),
                ),
                ctx,
            )

            result shouldContain "nonexistent.txt:"
            result shouldContain "Path does not exist"
        }

        "multiple patches in single edit should apply sequentially" {
            val tool = EditFilesTool(tempDir)
            val file = tempDir.resolve("file.txt")
            file.writeText("First line here\nSecond line here\nThird line here")

            val result = tool.execute(
                Input(
                    edits = listOf(
                        PatchEdit(
                            path = "file.txt",
                            replacements = listOf(
                                Replacement(oldPart = "First line here", newPart = "1st line modified"),
                                Replacement(oldPart = "Second line here", newPart = "2nd line modified"),
                            ),
                        ),
                    ),
                ),
                ctx,
            )

            result shouldContain "successfully edited by chunks"
            file.readText() shouldBe "1st line modified\n2nd line modified\nThird line here"
        }

        "empty edits list should return no edits provided" {
            val tool = EditFilesTool(tempDir)

            val result = tool.execute(Input(edits = emptyList()), ctx)

            result shouldContain "No edits provided"
        }

        "write should handle unicode content" {
            val tool = EditFilesTool(tempDir)

            val result = tool.execute(
                Input(
                    edits = listOf(
                        WriteEdit(path = "unicode.txt", content = "Hello 世界 🌍"),
                    ),
                ),
                ctx,
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
                    Input(
                        edits = listOf(
                            WriteEdit(path = "readonly/file.txt", content = "test content"),
                        ),
                    ),
                    ctx,
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
                Input(
                    edits = listOf(
                        WriteEdit(path = ".env", content = "SECRET=value"),
                    ),
                ),
                ctx,
            )

            result shouldContain ".env:"
            result shouldContain "Access denied"
            tempDir.resolve(".env").exists() shouldBe false
        }

        "deny-list write attempt to .env.local should be rejected" {
            val tool = EditFilesTool(tempDir)

            val result = tool.execute(
                Input(
                    edits = listOf(
                        WriteEdit(path = ".env.local", content = "SECRET=value"),
                    ),
                ),
                ctx,
            )

            result shouldContain ".env.local:"
            result shouldContain "Access denied"
            tempDir.resolve(".env.local").exists() shouldBe false
        }

        "deny-list patch attempt to .env should be rejected" {
            val tool = EditFilesTool(tempDir)
            tempDir.resolve(".env").writeText("OLD_SECRET=value")

            val result = tool.execute(
                Input(
                    edits = listOf(
                        PatchEdit(
                            path = ".env",
                            replacements = listOf(Replacement(oldPart = "OLD_SECRET=value", newPart = "NEW_SECRET=changed")),
                        ),
                    ),
                ),
                ctx,
            )

            result shouldContain ".env:"
            result shouldContain "Access denied"
            tempDir.resolve(".env").readText() shouldBe "OLD_SECRET=value"
        }

        // --- executeWithJson integration tests ---

        "executeWithJson should deserialize WriteEdit" {
            val tool = EditFilesTool(tempDir)

            val result = tool.executeWithJson(

                """
                {
                    "edits": [
                        {
                            "operation": "write",
                            "path": "hello.txt",
                            "content": "Hello from JSON"
                        }
                    ]
                }
                """,
                ctx,
            )

            result shouldContain "File write success"
            tempDir.resolve("hello.txt").readText() shouldBe "Hello from JSON"
        }

        "executeWithJson should deserialize PatchEdit" {
            val tool = EditFilesTool(tempDir)
            val file = tempDir.resolve("patch-me.txt")
            file.writeText("The quick brown fox jumps")

            val result = tool.executeWithJson(
                """
                {
                    "edits": [
                        {
                            "operation": "patch",
                            "path": "patch-me.txt",
                            "replacements": [
                                {
                                    "oldPart": "The quick brown fox",
                                    "newPart": "A slow grey cat"
                                }
                            ]
                        }
                    ]
                }
                """,
                ctx,
            )

            result shouldContain "successfully edited by chunks"
            file.readText() shouldBe "A slow grey cat jumps"
        }

        "executeWithJson should handle mixed write and patch" {
            val tool = EditFilesTool(tempDir)
            val existing = tempDir.resolve("existing.txt")
            existing.writeText("Replace this old content here")

            val result = tool.executeWithJson(
                """
                {
                    "edits": [
                        {
                            "operation": "write",
                            "path": "new.txt",
                            "content": "brand new file"
                        },
                        {
                            "operation": "patch",
                            "path": "existing.txt",
                            "replacements": [
                                {
                                    "oldPart": "Replace this old content",
                                    "newPart": "Keep this new content"
                                }
                            ]
                        }
                    ]
                }
                """,
                ctx,
            )

            result shouldContain "new.txt: File write success"
            result shouldContain "existing.txt: File successfully edited by chunks"
            tempDir.resolve("new.txt").readText() shouldBe "brand new file"
            existing.readText() shouldBe "Keep this new content here"
        }

        "executeWithJson with empty edits array should return no edits provided" {
            val tool = EditFilesTool(tempDir)

            val result = tool.executeWithJson(
                """
                {
                    "edits": []
                }
                """,
                ctx,
            )

            result shouldContain "No edits provided"
        }

        "executeWithJson should handle multiple replacements in PatchEdit" {
            val tool = EditFilesTool(tempDir)
            val file = tempDir.resolve("multi.txt")
            file.writeText("First chunk to replace\nSecond chunk to replace\nThird line stays")

            val result = tool.executeWithJson(
                """
                {
                    "edits": [
                        {
                            "operation": "patch",
                            "path": "multi.txt",
                            "replacements": [
                                {
                                    "oldPart": "First chunk to replace",
                                    "newPart": "First chunk replaced"
                                },
                                {
                                    "oldPart": "Second chunk to replace",
                                    "newPart": "Second chunk replaced"
                                }
                            ]
                        }
                    ]
                }
                """,
                ctx,
            )

            result shouldContain "successfully edited by chunks"
            file.readText() shouldBe "First chunk replaced\nSecond chunk replaced\nThird line stays"
        }

        "executeWithJson with missing optional fields should use defaults" {
            val tool = EditFilesTool(tempDir)

            val result = tool.executeWithJson(
                """
                {
                    "edits": [
                        {
                            "operation": "write",
                            "path": "defaults.txt"
                        }
                    ]
                }
                """,
                ctx,
            )

            result shouldContain "File write success"
            tempDir.resolve("defaults.txt").readText() shouldBe ""
        }
    }
}
