package io.whozoss.agentos.plugins.file

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.plugins.file.tools.ListFilesTool
import java.nio.file.Files
import kotlin.io.path.createFile
import kotlin.io.path.writeText

class ListFilesToolTest : StringSpec() {
    init {
        "listing normal directory should show files and dirs with / suffix" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ListFilesTool(tempDir)
                Files.createDirectories(tempDir.resolve("subdir"))
                tempDir.resolve("file1.txt").createFile()
                tempDir.resolve("file2.md").createFile()

                val result = tool.execute(ListFilesTool.Input("project://"))

                result shouldContain "subdir/"
                result shouldContain "file1.txt"
                result shouldContain "file2.md"
                result shouldNotContain "subdir\n"  // Should have slash
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "empty directory should return empty list" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ListFilesTool(tempDir)
                Files.createDirectories(tempDir.resolve("empty"))

                val result = tool.execute(ListFilesTool.Input("project://empty"))

                result shouldBe ""
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "path without prefix should error" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ListFilesTool(tempDir)

                val result = tool.execute(ListFilesTool.Input("somedir"))

                result shouldContain "must start with"
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

                val result = tool.execute(ListFilesTool.Input("project://"))

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

                val result = tool.execute(ListFilesTool.Input("project://"))

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

                val result = tool.execute(ListFilesTool.Input("project://file.txt"))

                result shouldContain "not a directory"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "empty or root paths should error requiring explicit prefix" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val tool = ListFilesTool(tempDir)

                val result1 = tool.execute(ListFilesTool.Input(""))
                val result2 = tool.execute(ListFilesTool.Input("."))
                val result3 = tool.execute(ListFilesTool.Input("/"))

                result1 shouldContain "must start with"
                result2 shouldContain "must start with"
                result3 shouldContain "must start with"
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

                val result = tool.execute(ListFilesTool.Input("project://a/b"))

                result shouldContain "file.txt"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    }
}
