package io.whozoss.agentos.plugins.file

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createFile

class FilePathResolverTest : StringSpec() {
    init {
        "valid relative path should resolve to absolute path within root" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val file = tempDir.resolve("file.txt").createFile()
                val resolver = BoundaryPathResolver(tempDir)

                val resolved = resolver.resolve("file.txt", createIntent = false)

                resolved shouldBe file.toRealPath()
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "nested path should resolve correctly" {
            val tempDir = Files.createTempDirectory("test")
            try {
                Files.createDirectories(tempDir.resolve("a/b/c"))
                val file = tempDir.resolve("a/b/c/file.txt").createFile()
                val resolver = BoundaryPathResolver(tempDir)

                val resolved = resolver.resolve("a/b/c/file.txt", createIntent = false)

                resolved shouldBe file.toRealPath()
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "path with symlink inside root should succeed" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val targetFile = tempDir.resolve("target.txt").createFile()
                val linkFile = tempDir.resolve("link.txt")
                Files.createSymbolicLink(linkFile, targetFile)
                val resolver = BoundaryPathResolver(tempDir)

                val resolved = resolver.resolve("link.txt", createIntent = false)

                resolved shouldBe targetFile.toRealPath()
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "deny-list should reject .env" {
            val tempDir = Files.createTempDirectory("test")
            try {
                tempDir.resolve(".env").createFile()
                val resolver = BoundaryPathResolver(tempDir, listOf(".env", "*.key"))

                val exception = shouldThrow<IllegalArgumentException> {
                    resolver.resolve(".env", createIntent = false)
                }

                exception.message shouldContain "Access denied"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "path traversal attempt should be rejected" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)

                val exception = shouldThrow<IllegalArgumentException> {
                    resolver.resolve("../outside.txt", createIntent = false)
                }

                exception.message shouldContain "path traversal not allowed"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "createIntent=true allows missing files" {
            val tempDir = Files.createTempDirectory("test")
            try {
                Files.createDirectories(tempDir.resolve("dir"))
                val resolver = BoundaryPathResolver(tempDir)

                val resolved = resolver.resolve("dir/newfile.txt", createIntent = true)

                resolved shouldBe tempDir.toRealPath().resolve("dir/newfile.txt")
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "createIntent=false on non-existent file should throw" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)

                val exception = shouldThrow<IllegalArgumentException> {
                    resolver.resolve("nonexistent.txt", createIntent = false)
                }

                exception.message shouldContain "Path does not exist"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "null byte in path should be rejected" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)

                val exception = shouldThrow<IllegalArgumentException> {
                    resolver.resolve("file\u0000.txt", createIntent = false)
                }

                exception.message shouldContain "illegal characters"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "empty relative path resolves to root" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)

                val resolved = resolver.resolve("", createIntent = false)

                resolved shouldBe tempDir.toRealPath()
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    }
}
