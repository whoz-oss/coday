package io.whozoss.agentos.plugins.file

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.writeText

class BoundaryPathResolverTest : StringSpec() {
    init {
        "traversal lexical normal - segment by segment without symlink should succeed" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)
                Files.createDirectories(tempDir.resolve("dir1/dir2"))
                val file = tempDir.resolve("dir1/dir2/file.txt").createFile()

                val resolved = resolver.resolve("dir1/dir2/file.txt", createIntent = false)

                resolved shouldBe file.toRealPath()
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "symlink intermediate (middle of path) to target under root should succeed" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)
                val targetDir = tempDir.resolve("target").also { Files.createDirectories(it) }
                val file = targetDir.resolve("file.txt").createFile()
                val linkDir = tempDir.resolve("link")
                Files.createSymbolicLink(linkDir, targetDir)

                val resolved = resolver.resolve("link/file.txt", createIntent = false)

                resolved shouldBe file.toRealPath()
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "symlink intermediate to target outside root should reject with boundary escape message" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val outsideDir = Files.createTempDirectory("outside")
                try {
                    val resolver = BoundaryPathResolver(tempDir)
                    Files.createDirectories(tempDir.resolve("subdir"))
                    Files.createSymbolicLink(tempDir.resolve("subdir/link"), outsideDir)

                    val exception = shouldThrow<IllegalArgumentException> {
                        resolver.resolve("subdir/link/file.txt", createIntent = false)
                    }

                    exception.message shouldContain "Symlink escapes boundary"
                } finally {
                    outsideDir.toFile().deleteRecursively()
                }
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "symlink final to target under root should succeed" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)
                val targetFile = tempDir.resolve("target.txt").also { it.writeText("content") }
                val linkFile = tempDir.resolve("link.txt")
                Files.createSymbolicLink(linkFile, targetFile)

                val resolved = resolver.resolve("link.txt", createIntent = false)

                resolved shouldBe targetFile.toRealPath()
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "symlink final to target outside root should reject" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val outsideFile = Files.createTempFile("outside", ".txt")
                try {
                    val resolver = BoundaryPathResolver(tempDir)
                    Files.createSymbolicLink(tempDir.resolve("link.txt"), outsideFile)

                    val exception = shouldThrow<IllegalArgumentException> {
                        resolver.resolve("link.txt", createIntent = false)
                    }

                    exception.message shouldContain "Symlink escapes boundary"
                } finally {
                    Files.deleteIfExists(outsideFile)
                }
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "chain of symlinks all under root should succeed" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)
                val finalTarget = tempDir.resolve("final.txt").also { it.writeText("content") }
                val link1 = tempDir.resolve("link1.txt")
                val link2 = tempDir.resolve("link2.txt")
                Files.createSymbolicLink(link2, finalTarget)
                Files.createSymbolicLink(link1, link2)

                val resolved = resolver.resolve("link1.txt", createIntent = false)

                resolved shouldBe finalTarget.toRealPath()
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "chain with one link outside root should reject at first invalid link" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val outsideFile = Files.createTempFile("outside", ".txt")
                try {
                    val resolver = BoundaryPathResolver(tempDir)
                    val link1 = tempDir.resolve("link1.txt")
                    Files.createSymbolicLink(link1, outsideFile)

                    val exception = shouldThrow<IllegalArgumentException> {
                        resolver.resolve("link1.txt", createIntent = false)
                    }

                    exception.message shouldContain "Symlink escapes boundary"
                } finally {
                    Files.deleteIfExists(outsideFile)
                }
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "path traversal with ../ resolved lexically should reject if escapes root" {
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

        "path traversal encoded %2F.. should reject before traversal" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)

                val exception = shouldThrow<IllegalArgumentException> {
                    resolver.resolve("dir%2F../file.txt", createIntent = false)
                }

                exception.message shouldContain "illegal characters detected"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "null byte in path should reject" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)

                val exception = shouldThrow<IllegalArgumentException> {
                    resolver.resolve("file\u0000.txt", createIntent = false)
                }

                exception.message shouldContain "illegal characters detected"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "segment exceeding 255 chars should reject" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)
                val longSegment = "a".repeat(256)

                val exception = shouldThrow<IllegalArgumentException> {
                    resolver.resolve(longSegment, createIntent = false)
                }

                exception.message shouldContain "segment exceeds 255 characters"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "missing file with createIntent=true should succeed" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)
                Files.createDirectories(tempDir.resolve("dir"))

                val resolved = resolver.resolve("dir/newfile.txt", createIntent = true)

                resolved shouldBe tempDir.toRealPath().resolve("dir/newfile.txt")
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "missing file with createIntent=false should error" {
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

        "deny-list .env file should reject with access denied" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)
                tempDir.resolve(".env").createFile()

                val exception = shouldThrow<IllegalArgumentException> {
                    resolver.resolve(".env", createIntent = false)
                }

                exception.message shouldContain "Access denied"
                exception.message shouldContain ".env"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "deny-list credentials.json file should reject" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)
                tempDir.resolve("credentials.json").createFile()

                val exception = shouldThrow<IllegalArgumentException> {
                    resolver.resolve("credentials.json", createIntent = false)
                }

                exception.message shouldContain "Access denied"
                exception.message shouldContain "credentials.json"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "deny-list pattern *.key matching private.key should reject" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)
                tempDir.resolve("private.key").createFile()

                val exception = shouldThrow<IllegalArgumentException> {
                    resolver.resolve("private.key", createIntent = false)
                }

                exception.message shouldContain "Access denied"
                exception.message shouldContain "*.key"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "normal file not in deny-list should succeed" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)
                val normalFile = tempDir.resolve("normal.txt").createFile()

                val resolved = resolver.resolve("normal.txt", createIntent = false)

                resolved shouldBe normalFile.toRealPath()
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "empty path should resolve to root" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)

                val resolved = resolver.resolve("", createIntent = false)

                resolved shouldBe tempDir.toRealPath()
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "URL encoding %00 (null byte) should reject" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)

                val exception = shouldThrow<IllegalArgumentException> {
                    resolver.resolve("file%00.txt", createIntent = false)
                }

                exception.message shouldContain "illegal characters detected"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "URL encoding %5C (backslash) should reject" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)

                val exception = shouldThrow<IllegalArgumentException> {
                    resolver.resolve("dir%5Cfile.txt", createIntent = false)
                }

                exception.message shouldContain "illegal characters detected"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "deny-list pattern .env.* matching .env.local should reject" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)
                tempDir.resolve(".env.local").createFile()

                val exception = shouldThrow<IllegalArgumentException> {
                    resolver.resolve(".env.local", createIntent = false)
                }

                exception.message shouldContain "Access denied"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "missing intermediate directory with createIntent=true should append remaining segments" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)

                val resolved = resolver.resolve("newdir/subdir/file.txt", createIntent = true)

                resolved shouldBe tempDir.toRealPath().resolve("newdir/subdir/file.txt")
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "symlink in middle of chain with missing final segment and createIntent=true should succeed" {
            val tempDir = Files.createTempDirectory("test")
            try {
                val resolver = BoundaryPathResolver(tempDir)
                val targetDir = tempDir.resolve("target").also { Files.createDirectories(it) }
                val linkDir = tempDir.resolve("link")
                Files.createSymbolicLink(linkDir, targetDir)

                val resolved = resolver.resolve("link/newfile.txt", createIntent = true)

                resolved shouldBe targetDir.toRealPath().resolve("newfile.txt")
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    }
}
