package io.whozoss.agentos.plugins.gradle

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.nio.file.Files

/** Writes an executable fake `gradlew` running [body] into [dir] (created when needed). */
private fun fakeGradlew(
    dir: File,
    body: String,
): File =
    dir.resolve("gradlew").apply {
        parentFile.mkdirs()
        writeText("#!/bin/sh\n$body\n")
        setExecutable(true)
    }

/**
 * What the three tools do with the `gradlew` of the integration directory and with a build that includes other
 * builds. The resolution rules themselves are in [GradleRunnerUnitSpec].
 */
class GradleToolBuildLocationUnitSpec :
    StringSpec({

        // A call per tool, so the same rule is checked on the three of them.
        class Call(
            val label: String,
            val run: (GradleRunner, String?) -> String,
        )

        val calls =
            listOf(
                Call("gradle_compile") { runner, subproject ->
                    GradleCompileTool(runner).compile(subproject = subproject, timeoutSeconds = 60).toToolExecutionResult().output
                },
                Call("gradle_tests") { runner, subproject ->
                    GradleTestTool(runner).runTests(testFilter = null, subproject = subproject, timeoutSeconds = 60).toToolExecutionResult().output
                },
                Call("gradle_dependency_insight") { runner, subproject ->
                    GradleDependencyInsightTool(runner)
                        .dependencyInsight(dependency = "x", subproject = subproject, timeoutSeconds = 60)
                        .toToolExecutionResult()
                        .output
                },
            )

        calls.forEach { call ->
            "${call.label} runs the gradlew of the integration directory, from that directory" {
                val root = Files.createTempDirectory("agentos-build-location").toFile()
                try {
                    val backend = root.resolve("backend")
                    fakeGradlew(backend, "echo ran in \$(pwd)\nexit 1")

                    val result = call.run(GradleRunner(root, gradlewDirectory = backend), null)

                    result shouldContain "ran in"
                    result shouldContain "backend"
                } finally {
                    root.deleteRecursively()
                }
            }

            "${call.label} does not run a gradlew found above or below the integration directory" {
                val root = Files.createTempDirectory("agentos-build-other-gradlew").toFile()
                try {
                    val marker = root.resolve("ran")
                    fakeGradlew(root, "touch '${marker.path}'")
                    fakeGradlew(root.resolve("project/nested"), "touch '${marker.path}'")

                    val result = call.run(GradleRunner(root.resolve("project").also { it.mkdirs() }), null)

                    result shouldStartWith "ERROR"
                    result shouldContain "gradlewDirectory"
                    marker.exists() shouldBe false
                } finally {
                    root.deleteRecursively()
                }
            }

            "${call.label} adds the composite build hint when a run without subproject fails" {
                val root = Files.createTempDirectory("agentos-composite-fail").toFile()
                try {
                    root.resolve("settings.gradle.kts").writeText("""includeBuild("agentos-sdk")""")
                    fakeGradlew(root, "exit 1")

                    val result = call.run(GradleRunner(root), null)

                    result shouldContain "agentos-sdk"
                    result shouldContain "retry with 'subproject'"
                } finally {
                    root.deleteRecursively()
                }
            }

            "${call.label} adds no composite build hint when a subproject is given" {
                val root = Files.createTempDirectory("agentos-composite-module").toFile()
                try {
                    root.resolve("settings.gradle.kts").writeText("""includeBuild("agentos-sdk")""")
                    fakeGradlew(root, "exit 1")

                    val result = call.run(GradleRunner(root), "agentos-sdk")

                    result shouldNotContain "includes other builds"
                } finally {
                    root.deleteRecursively()
                }
            }

            "${call.label} adds no composite build hint when the run succeeds" {
                val root = Files.createTempDirectory("agentos-composite-ok").toFile()
                try {
                    root.resolve("settings.gradle.kts").writeText("""includeBuild("agentos-sdk")""")
                    fakeGradlew(root, "exit 0")

                    val result = call.run(GradleRunner(root), null)

                    result shouldNotContain "includes other builds"
                } finally {
                    root.deleteRecursively()
                }
            }
        }
    })
