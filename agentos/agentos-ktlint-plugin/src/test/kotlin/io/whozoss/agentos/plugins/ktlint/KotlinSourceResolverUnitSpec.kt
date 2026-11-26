package io.whozoss.agentos.plugins.ktlint

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class KotlinSourceResolverUnitSpec :
    StringSpec({

        "resolves a Kotlin file given by its relative path" {
            withProject { projectDir ->
                val file = projectDir.writeFile(relativePath = "src/Sample.kt")
                val resolver = KotlinSourceResolver(projectRoot = projectDir)

                resolver.resolve(relativePaths = listOf("src/Sample.kt")) shouldContainExactly listOf(file)
            }
        }

        "resolves a Kotlin script file" {
            withProject { projectDir ->
                val script = projectDir.writeFile(relativePath = "build.gradle.kts")
                val resolver = KotlinSourceResolver(projectRoot = projectDir)

                resolver.resolve(relativePaths = listOf("build.gradle.kts")) shouldContainExactly listOf(script)
            }
        }

        "rejects a directory: ktlint runs file by file, never on a whole tree" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "src/Sample.kt")
                val resolver = KotlinSourceResolver(projectRoot = projectDir)

                val exception = shouldThrow<IllegalArgumentException> { resolver.resolve(relativePaths = listOf("src")) }

                exception.message shouldContain "is a directory"
                exception.message shouldContain "src"
            }
        }

        "rejects the project root given as a directory" {
            withProject { projectDir ->
                val resolver = KotlinSourceResolver(projectRoot = projectDir)

                val exception = shouldThrow<IllegalArgumentException> { resolver.resolve(relativePaths = listOf(".")) }

                exception.message shouldContain "is a directory"
            }
        }

        "returns each file once and sorted when it is listed several times" {
            withProject { projectDir ->
                val first = projectDir.writeFile(relativePath = "src/A.kt")
                val second = projectDir.writeFile(relativePath = "src/B.kt")
                val resolver = KotlinSourceResolver(projectRoot = projectDir)

                val resolved = resolver.resolve(relativePaths = listOf("src/B.kt", "src/A.kt", "src/B.kt"))

                resolved shouldContainExactly listOf(first, second)
            }
        }

        "rejects a path escaping the project root" {
            withProject { projectDir ->
                val resolver = KotlinSourceResolver(projectRoot = projectDir)

                val exception = shouldThrow<IllegalArgumentException> { resolver.resolve(relativePaths = listOf("../outside.kt")) }

                exception.message shouldContain "outside the project"
            }
        }

        "rejects a symbolic link pointing outside the project root" {
            withProject { projectDir ->
                withProject { outsideDir ->
                    outsideDir.writeFile(relativePath = "Secret.kt")
                    Files.createSymbolicLink(projectDir.resolve("link"), outsideDir)
                    val resolver = KotlinSourceResolver(projectRoot = projectDir)

                    val exception =
                        shouldThrow<IllegalArgumentException> { resolver.resolve(relativePaths = listOf("link/Secret.kt")) }

                    exception.message shouldContain "outside the project"
                }
            }
        }

        "rejects a symbolic link to a file outside the project root" {
            withProject { projectDir ->
                withProject { outsideDir ->
                    val secret = outsideDir.writeFile(relativePath = "Secret.kt")
                    Files.createSymbolicLink(projectDir.resolve("Linked.kt"), secret)
                    val resolver = KotlinSourceResolver(projectRoot = projectDir)

                    val exception = shouldThrow<IllegalArgumentException> { resolver.resolve(relativePaths = listOf("Linked.kt")) }

                    exception.message shouldContain "outside the project"
                }
            }
        }

        "accepts a symbolic link to a file that stays inside the project root, resolved to its real path" {
            withProject { projectDir ->
                val target = projectDir.writeFile(relativePath = "src/Target.kt")
                Files.createSymbolicLink(projectDir.resolve("Linked.kt"), target)
                val resolver = KotlinSourceResolver(projectRoot = projectDir)

                resolver.resolve(relativePaths = listOf("Linked.kt")) shouldContainExactly listOf(target)
            }
        }

        "rejects a path that does not exist" {
            withProject { projectDir ->
                val resolver = KotlinSourceResolver(projectRoot = projectDir)

                val exception = shouldThrow<IllegalArgumentException> { resolver.resolve(relativePaths = listOf("src/Missing.kt")) }

                exception.message shouldContain "not found"
            }
        }

        "rejects a file that is not Kotlin source" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "README.md", content = "# readme")
                val resolver = KotlinSourceResolver(projectRoot = projectDir)

                val exception = shouldThrow<IllegalArgumentException> { resolver.resolve(relativePaths = listOf("README.md")) }

                exception.message shouldContain "not a Kotlin"
            }
        }
    })
