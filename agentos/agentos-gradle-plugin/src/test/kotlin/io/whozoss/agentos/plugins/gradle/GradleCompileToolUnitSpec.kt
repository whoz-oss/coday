package io.whozoss.agentos.plugins.gradle

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.nio.file.Files

class GradleCompileToolUnitSpec :
    StringSpec({

        // ── buildCommand ───────────────────────────────────────────────────────────────────────

        "buildCommand with no subproject runs plain testClasses task" {
            val tool = GradleCompileTool(GradleRunner(File("/fake")))
            val cmd = tool.buildCommand(gradlew = "/fake/gradlew", subproject = null)

            cmd shouldContainInOrder listOf("/fake/gradlew", "testClasses")
        }

        "buildCommand with subproject targets subproject testClasses task" {
            val tool = GradleCompileTool(GradleRunner(File("/fake")))
            val cmd = tool.buildCommand(gradlew = "/fake/gradlew", subproject = "cli")

            cmd shouldContainInOrder listOf("/fake/gradlew", ":cli:testClasses")
        }

        "buildCommand with leading-colon subproject strips and re-adds colon" {
            val tool = GradleCompileTool(GradleRunner(File("/fake")))
            val cmd = tool.buildCommand(gradlew = "/fake/gradlew", subproject = ":engine")

            cmd shouldContainInOrder listOf("/fake/gradlew", ":engine:testClasses")
        }

        "buildCommand includes --continue --quiet --console=plain flags" {
            val tool = GradleCompileTool(GradleRunner(File("/fake")))
            val cmd = tool.buildCommand(gradlew = "/fake/gradlew", subproject = null)

            cmd shouldContainInOrder listOf("--continue", "--quiet", "--console=plain")
        }

        // ── resolveTargets ────────────────────────────────────────────────────────────────────

        "resolveTargets with both null compiles everything" {
            val tool = GradleCompileTool(GradleRunner(File("/fake")))
            val result = tool.resolveTargets(resolvedSubproject = null, resolvedFile = null)

            result shouldBe (null to null)
        }

        "resolveTargets with subproject only returns it with null filter" {
            val tool = GradleCompileTool(GradleRunner(File("/fake")))
            val result = tool.resolveTargets(resolvedSubproject = "cli", resolvedFile = null)

            result shouldBe ("cli" to null)
        }

        "resolveTargets with subproject and file returns subproject with file as filter" {
            val tool = GradleCompileTool(GradleRunner(File("/fake")))
            val result =
                tool.resolveTargets(
                    resolvedSubproject = "engine",
                    resolvedFile = "engine/src/main/kotlin/Foo.kt",
                )

            result shouldBe ("engine" to "engine/src/main/kotlin/Foo.kt")
        }

        // ── detectModule ─────────────────────────────────────────────────────────────────────

        "detectModule resolves subproject from file inside module dir" {
            val root = Files.createTempDirectory("agentos-compile-detect").toFile()
            try {
                // layout: root/settings.gradle.kts + root/cli/build.gradle.kts + source file
                root.resolve("settings.gradle.kts").createNewFile()
                val cliDir = root.resolve("cli").also { it.mkdirs() }
                cliDir.resolve("build.gradle.kts").createNewFile()
                val srcFile =
                    cliDir.resolve("src/main/kotlin/Foo.kt").also {
                        it.parentFile.mkdirs()
                        it.createNewFile()
                    }

                val tool = GradleCompileTool(GradleRunner(root))
                tool.detectModule(srcFile) shouldBe "cli"
            } finally {
                root.deleteRecursively()
            }
        }

        "detectModule returns null when no build script found" {
            val root = Files.createTempDirectory("agentos-compile-no-build").toFile()
            try {
                root.resolve("settings.gradle.kts").createNewFile()
                val orphan =
                    root.resolve("orphan/Foo.kt").also {
                        it.parentFile.mkdirs()
                        it.createNewFile()
                    }

                val tool = GradleCompileTool(GradleRunner(root))
                tool.detectModule(orphan) shouldBe null
            } finally {
                root.deleteRecursively()
            }
        }

        "detectModule returns null when file is directly in the root (no sub-module)" {
            val root = Files.createTempDirectory("agentos-compile-root-file").toFile()
            try {
                root.resolve("settings.gradle.kts").createNewFile()
                root.resolve("build.gradle.kts").createNewFile()
                val srcFile =
                    root.resolve("src/main/kotlin/Root.kt").also {
                        it.parentFile.mkdirs()
                        it.createNewFile()
                    }

                val tool = GradleCompileTool(GradleRunner(root))
                // Root module has no meaningful sub-name → null
                tool.detectModule(srcFile) shouldBe null
            } finally {
                root.deleteRecursively()
            }
        }

        "detectModule resolves nested subproject path with colons" {
            val root = Files.createTempDirectory("agentos-compile-nested").toFile()
            try {
                root.resolve("settings.gradle.kts").createNewFile()
                val featureDir = root.resolve("feature/core").also { it.mkdirs() }
                featureDir.resolve("build.gradle.kts").createNewFile()
                val srcFile =
                    featureDir.resolve("src/main/kotlin/Bar.kt").also {
                        it.parentFile.mkdirs()
                        it.createNewFile()
                    }

                val tool = GradleCompileTool(GradleRunner(root))
                tool.detectModule(srcFile) shouldBe "feature:core"
            } finally {
                root.deleteRecursively()
            }
        }

        // ── resolveProjectRoot ───────────────────────────────────────────────────────────────

        "resolveProjectRoot finds root containing settings.gradle.kts" {
            val root = Files.createTempDirectory("agentos-compile-prjroot").toFile()
            try {
                root.resolve("settings.gradle.kts").createNewFile()
                val sub = root.resolve("sub").also { it.mkdirs() }

                val tool = GradleCompileTool(GradleRunner(sub))
                tool.resolveProjectRoot() shouldBe root
            } finally {
                root.deleteRecursively()
            }
        }

        "resolveProjectRoot returns null when no settings file found" {
            val isolated = Files.createTempDirectory("agentos-compile-no-settings").toFile()
            try {
                val tool = GradleCompileTool(GradleRunner(isolated))
                tool.resolveProjectRoot() shouldBe null
            } finally {
                isolated.deleteRecursively()
            }
        }

        // ── resolveTargets with file auto-detection ──────────────────────────────────────────────────

        "resolveTargets with file auto-detects module and returns file as filter" {
            val root = Files.createTempDirectory("agentos-compile-autod").toFile()
            try {
                root.resolve("settings.gradle.kts").createNewFile()
                val cliDir = root.resolve("cli").also { it.mkdirs() }
                cliDir.resolve("build.gradle.kts").createNewFile()
                val srcFile =
                    cliDir.resolve("src/main/kotlin/Foo.kt").also {
                        it.parentFile.mkdirs()
                        it.createNewFile()
                    }

                val tool = GradleCompileTool(GradleRunner(root))
                val result =
                    tool.resolveTargets(
                        resolvedSubproject = null,
                        resolvedFile = srcFile.absolutePath,
                    )

                result shouldNotBe null
                result!!.first shouldBe "cli"
                result.second shouldBe srcFile.absolutePath
            } finally {
                root.deleteRecursively()
            }
        }

        "resolveTargets returns null when file has no owning module" {
            val root = Files.createTempDirectory("agentos-compile-no-owner").toFile()
            try {
                root.resolve("settings.gradle.kts").createNewFile()
                val orphan =
                    root.resolve("orphan/Foo.kt").also {
                        it.parentFile.mkdirs()
                        it.createNewFile()
                    }

                val tool = GradleCompileTool(GradleRunner(root))
                val result =
                    tool.resolveTargets(
                        resolvedSubproject = null,
                        resolvedFile = orphan.absolutePath,
                    )

                result shouldBe null
            } finally {
                root.deleteRecursively()
            }
        }

        // ── compile output formatting ─────────────────────────────────────────────────────────

        "compile returns ERROR message when resolveTargets returns null" {
            val root = Files.createTempDirectory("agentos-compile-err-msg").toFile()
            try {
                root.resolve("settings.gradle.kts").createNewFile()
                val orphan =
                    root.resolve("orphan/Foo.kt").also {
                        it.parentFile.mkdirs()
                        it.createNewFile()
                    }

                val tool = GradleCompileTool(GradleRunner(root))
                val result = tool.compile(subproject = null, file = orphan.absolutePath, timeoutSeconds = 60)

                result shouldContain "ERROR"
                result shouldContain "Could not locate a Gradle module"
            } finally {
                root.deleteRecursively()
            }
        }

        "compile returns an actionable ERROR when no gradlew can be found" {
            val root = Files.createTempDirectory("agentos-compile-no-gradlew").toFile()
            try {
                val result = GradleCompileTool(GradleRunner(root)).compile(subproject = null, file = null, timeoutSeconds = 60)

                result shouldStartWith "ERROR"
                result shouldContain "gradlewPath"
            } finally {
                root.deleteRecursively()
            }
        }

        // ── timeoutSeconds ─────────────────────────────────────────────────────────────────

        "tool schema marks timeoutSeconds as required so the agent must always provide it" {
            val tool = GradleCompileTool(GradleRunner(File("/fake")))

            tool.name shouldBe "gradle_compile"
            tool.inputSchema shouldMatch Regex("""(?s).*"required"\s*:\s*\[[^\]]*"timeoutSeconds".*""")
        }

        "compile times out after timeoutSeconds, says so and keeps the partial output" {
            val root = Files.createTempDirectory("agentos-compile-timeout").toFile()
            try {
                root.resolve("gradlew").apply {
                    writeText("#!/bin/sh\necho partial output\nsleep 60\n")
                    setExecutable(true)
                }

                val result = GradleCompileTool(GradleRunner(root)).compile(subproject = null, file = null, timeoutSeconds = 1)

                result shouldStartWith "ERROR"
                result shouldContain "timed out after 1 seconds"
                result shouldContain "partial output"
            } finally {
                root.deleteRecursively()
            }
        }

        "compile rejects a timeoutSeconds outside 1..300 before doing anything else" {
            val tool = GradleCompileTool(GradleRunner(File("/fake")))
            listOf(0, -5, 301).forEach { timeoutSeconds ->
                val result = tool.compile(subproject = null, file = null, timeoutSeconds = timeoutSeconds)

                result shouldStartWith "ERROR"
                result shouldContain "timeoutSeconds"
                result shouldContain "300"
            }
        }
    })
