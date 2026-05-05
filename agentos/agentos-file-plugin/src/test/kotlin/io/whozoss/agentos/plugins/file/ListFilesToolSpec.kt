package io.whozoss.agentos.plugins.file

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.plugins.file.tools.ListFilesTool
import io.whozoss.agentos.sdk.tool.ToolContext
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.createFile
import kotlin.io.path.writeText

class ListFilesToolSpec : StringSpec() {
    private val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())

    init {
        "listing normal directory should show files and dirs with / suffix" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ListFilesTool(tempDir)
                Files.createDirectories(tempDir.resolve("subdir"))
                tempDir.resolve("file1.txt").createFile()
                tempDir.resolve("file2.md").createFile()

                val result = tool.execute(ListFilesTool.Input(""), ctx)

                result shouldContain "subdir/"
                result shouldContain "file1.txt"
                result shouldContain "file2.md"
                result shouldNotContain "subdir\n"  // Should have slash
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "dot should also list root directory" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ListFilesTool(tempDir)
                tempDir.resolve("file.txt").createFile()

                val result = tool.execute(ListFilesTool.Input("."), ctx)

                result shouldContain "file.txt"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "empty directory should return empty list" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ListFilesTool(tempDir)
                Files.createDirectories(tempDir.resolve("empty"))

                val result = tool.execute(ListFilesTool.Input("empty"), ctx)

                result shouldBe ""
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "valid symlinks should be displayed" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ListFilesTool(tempDir)
                val targetFile = tempDir.resolve("target.txt").also { it.writeText("content") }
                val linkFile = tempDir.resolve("link.txt")
                Files.createSymbolicLink(linkFile, targetFile)

                val result = tool.execute(ListFilesTool.Input(""), ctx)

                result shouldContain "link.txt"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "broken symlinks should be marked as inaccessible" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ListFilesTool(tempDir)
                val nonexistent = tempDir.resolve("nonexistent.txt")
                val linkFile = tempDir.resolve("broken-link.txt")
                Files.createSymbolicLink(linkFile, nonexistent)

                val result = tool.execute(ListFilesTool.Input(""), ctx)

                result shouldContain "broken-link.txt"
                result shouldContain "inaccessible"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "listing non-directory should error" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ListFilesTool(tempDir)
                tempDir.resolve("file.txt").createFile()

                val result = tool.execute(ListFilesTool.Input("file.txt"), ctx)

                result shouldContain "not a directory"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "nested directory listing should work" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ListFilesTool(tempDir)
                Files.createDirectories(tempDir.resolve("a/b"))
                tempDir.resolve("a/b/file.txt").createFile()

                val result = tool.execute(ListFilesTool.Input("a/b"), ctx)

                result shouldContain "file.txt"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    }
}
