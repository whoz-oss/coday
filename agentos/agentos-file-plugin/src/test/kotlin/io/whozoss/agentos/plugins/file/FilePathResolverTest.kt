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
        "project:// prefix valid should resolve correctly via BoundaryPathResolver" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val fileRoots = mapOf("project" to tempDir)
                val file = tempDir.resolve("file.txt").createFile()

                val resolved = resolveFilePath("project://file.txt", fileRoots, createIntent = false)

                resolved.absolutePath shouldBe file.toRealPath()
                resolved.scope shouldBe FileScope.PROJECT
                resolved.relativePath shouldBe "file.txt"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "exchange:// prefix should error with V2 PLANNED message" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val fileRoots = mapOf("project" to tempDir)

                val exception = shouldThrow<IllegalArgumentException> {
                    resolveFilePath("exchange://file.txt", fileRoots, createIntent = false)
                }

                exception.message shouldContain "exchange://"
                exception.message shouldContain "not supported"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "no prefix should error with helpful message" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val fileRoots = mapOf("project" to tempDir)

                val exception = shouldThrow<IllegalArgumentException> {
                    resolveFilePath("file.txt", fileRoots, createIntent = false)
                }

                exception.message shouldContain "must start with"
                exception.message shouldContain "project://"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "path with symlink validated by BoundaryPathResolver should succeed" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val fileRoots = mapOf("project" to tempDir)
                val targetFile = tempDir.resolve("target.txt").createFile()
                val linkFile = tempDir.resolve("link.txt")
                Files.createSymbolicLink(linkFile, targetFile)

                val resolved = resolveFilePath("project://link.txt", fileRoots, createIntent = false)

                resolved.absolutePath shouldBe targetFile.toRealPath()
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "deny-list propagated to BoundaryPathResolver should reject correctly" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val fileRoots = mapOf("project" to tempDir)
                tempDir.resolve(".env").createFile()
                val customDenyList = listOf(".env", "*.key")

                val exception = shouldThrow<IllegalArgumentException> {
                    resolveFilePath("project://.env", fileRoots, createIntent = false, denyPatterns = customDenyList)
                }

                exception.message shouldContain "Access denied"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "missing project scope in fileRoots should error" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val fileRoots = emptyMap<String, Path>()

                val exception = shouldThrow<IllegalArgumentException> {
                    resolveFilePath("project://file.txt", fileRoots, createIntent = false)
                }

                exception.message shouldContain "No root configured for scope"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "nested path with multiple segments should resolve correctly" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val fileRoots = mapOf("project" to tempDir)
                Files.createDirectories(tempDir.resolve("a/b/c"))
                val file = tempDir.resolve("a/b/c/file.txt").createFile()

                val resolved = resolveFilePath("project://a/b/c/file.txt", fileRoots, createIntent = false)

                resolved.absolutePath shouldBe file.toRealPath()
                resolved.relativePath shouldBe "a/b/c/file.txt"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "createIntent=true allows missing files" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val fileRoots = mapOf("project" to tempDir)
                Files.createDirectories(tempDir.resolve("dir"))

                val resolved = resolveFilePath("project://dir/newfile.txt", fileRoots, createIntent = true)

                resolved.absolutePath shouldBe tempDir.toRealPath().resolve("dir/newfile.txt")
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "path traversal attempt should be rejected by BoundaryPathResolver" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val fileRoots = mapOf("project" to tempDir)

                val exception = shouldThrow<IllegalArgumentException> {
                    resolveFilePath("project://../outside.txt", fileRoots, createIntent = false)
                }

                exception.message shouldContain "path traversal not allowed"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    }
}
