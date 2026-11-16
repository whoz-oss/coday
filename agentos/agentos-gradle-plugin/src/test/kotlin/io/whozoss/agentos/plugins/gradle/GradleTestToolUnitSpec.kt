package io.whozoss.agentos.plugins.gradle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.whozoss.agentos.sdk.tool.ToolContext
import java.io.File
import java.nio.file.Files
import java.util.UUID

private const val REPORT_XML =
    """<?xml version="1.0" encoding="UTF-8"?><testsuite name="s" tests="1" skipped="0" failures="0" errors="0"><testcase name="t" classname="c"/></testsuite>"""

private const val INIT_SCRIPT = "/fake/init.gradle"

private const val RUN_ID = "run-1"

private fun toolIn(root: File) = GradleTestTool(GradleRunner(root), newRunId = { RUN_ID })

private fun writeReport(
    root: File,
    runId: String,
    name: String = "TEST-old.xml",
) {
    root.resolve("build/agentos-test-results/$runId/test").mkdirs()
    root.resolve("build/agentos-test-results/$runId/test/$name").writeText(REPORT_XML)
}

class GradleTestToolUnitSpec :
    StringSpec({

        val tool = GradleTestTool(File("/fake"))
        val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())

        // --- buildCommand ---

        "buildCommand with no subproject runs plain test task" {
            val cmd = tool.buildCommand(gradlew = "/fake/gradlew", initScript = INIT_SCRIPT, tasks = listOf(TestTask(subproject = null)))

            cmd shouldContainInOrder listOf("/fake/gradlew", "test")
            cmd shouldNotContain "--tests"
            cmd shouldContainInOrder listOf("-PtestBalloon.jvmTestBalloonTestsOnly=true")
        }

        "buildCommand with subproject targets subproject test task" {
            val cmd = tool.buildCommand(gradlew = "/fake/gradlew", initScript = INIT_SCRIPT, tasks = listOf(TestTask(subproject = "cli")))

            cmd shouldContainInOrder listOf("/fake/gradlew", ":cli:test")
            cmd shouldNotContain "test" // bare 'test' task must not appear
            cmd shouldContainInOrder listOf("-PtestBalloon.jvmTestBalloonTestsOnly=true")
        }

        "buildCommand with leading-colon subproject strips and re-adds colon" {
            val cmd = tool.buildCommand(gradlew = "/fake/gradlew", initScript = INIT_SCRIPT, tasks = listOf(TestTask(subproject = ":cli")))

            cmd shouldContainInOrder listOf("/fake/gradlew", ":cli:test")
        }

        "buildCommand with several tasks binds each --tests to its own task" {
            val cmd =
                tool.buildCommand(
                    gradlew = "/fake/gradlew",
                    initScript = INIT_SCRIPT,
                    tasks =
                        listOf(
                            TestTask("a", listOf("pkg.A*", "pkg.B*")),
                            TestTask(":b", listOf("pkg.C*")),
                        ),
                    testBalloonActive = false,
                )

            cmd shouldContainExactly
                listOf(
                    "/fake/gradlew",
                    "-I",
                    INIT_SCRIPT,
                    "--continue",
                    "--quiet",
                    "--console=plain",
                    ":a:test",
                    "--tests",
                    "pkg.A*",
                    "--tests",
                    "pkg.B*",
                    ":b:test",
                    "--tests",
                    "pkg.C*",
                )
        }

        "buildCommand never forces a rerun so Gradle up-to-date and build cache stay usable" {
            val cmd =
                tool.buildCommand(
                    gradlew = "/fake/gradlew",
                    initScript = INIT_SCRIPT,
                    tasks = listOf(TestTask(null), TestTask("a"), TestTask("b", listOf("pkg.B*"))),
                )

            cmd shouldNotContain "--rerun"
            cmd shouldNotContain "--rerun-tasks"
        }

        "buildCommand passes the init script to Gradle before the tasks" {
            val cmd = tool.buildCommand(gradlew = "/fake/gradlew", initScript = INIT_SCRIPT, tasks = listOf(TestTask("a")))

            cmd shouldContainInOrder listOf("/fake/gradlew", "-I", INIT_SCRIPT, ":a:test")
        }

        "buildCommand with a task without filters adds no --tests for it" {
            val cmd =
                tool.buildCommand(
                    gradlew = "/fake/gradlew",
                    initScript = INIT_SCRIPT,
                    tasks = listOf(TestTask("a", listOf("pkg.A*")), TestTask("b")),
                    testBalloonActive = false,
                )

            cmd shouldContainInOrder listOf(":a:test", "--tests", "pkg.A*", ":b:test")
            cmd.last() shouldBe ":b:test"
        }

        "buildCommand ignores testBalloon filters when binding --tests" {
            val cmd =
                tool.buildCommand(
                    gradlew = "/fake/gradlew",
                    initScript = INIT_SCRIPT,
                    tasks = listOf(TestTask("a", listOf("pkg.suite↘*", "pkg.A*"))),
                )

            cmd shouldContainInOrder listOf(":a:test", "--tests", "pkg.A*")
            cmd shouldNotContain "pkg.suite↘*"
        }

        // --- resolveTasks ---

        "resolveTasks with legacy params builds one task" {
            tool.resolveTasks(listOf("pkg.A*"), "cli", null) shouldBe listOf(TestTask("cli", listOf("pkg.A*")))
        }

        "resolveTasks with no params builds one plain task" {
            tool.resolveTasks(null, null, null) shouldBe listOf(TestTask(null))
        }

        "resolveTasks with targets builds one task per target" {
            tool.resolveTasks(
                null,
                null,
                listOf(TestTarget("a", listOf("pkg.A*")), TestTarget("b")),
            ) shouldBe listOf(TestTask("a", listOf("pkg.A*")), TestTask("b"))
        }

        "resolveTasks drops blank filters" {
            tool.resolveTasks(null, null, listOf(TestTarget("a", listOf("", " ", "pkg.A*")))) shouldBe
                listOf(TestTask("a", listOf("pkg.A*")))
        }

        "resolveTasks rejects targets mixed with legacy testFilter" {
            shouldThrow<IllegalArgumentException> {
                tool.resolveTasks(listOf("pkg.A*"), null, listOf(TestTarget("a")))
            }.message shouldContain "targets"
        }

        "resolveTasks rejects targets mixed with legacy subproject" {
            shouldThrow<IllegalArgumentException> {
                tool.resolveTasks(null, "cli", listOf(TestTarget("a")))
            }.message shouldContain "targets"
        }

        "resolveTasks rejects a target with blank subproject" {
            shouldThrow<IllegalArgumentException> {
                tool.resolveTasks(null, null, listOf(TestTarget(" ")))
            }.message shouldContain "subproject"
        }

        "resolveTasks treats empty targets as absent" {
            tool.resolveTasks(listOf("pkg.A*"), "cli", emptyList()) shouldBe listOf(TestTask("cli", listOf("pkg.A*")))
        }

        // --- buildIncludePatterns ---

        "buildIncludePatterns with empty list returns null" {
            tool.buildIncludePatterns(emptyList()) shouldBe null
        }

        "buildIncludePatterns with single filter returns it as-is" {
            tool.buildIncludePatterns(listOf("fr.tokazio.okia.cli.TerminalOperationsTests")) shouldBe
                "fr.tokazio.okia.cli.TerminalOperationsTests"
        }

        "buildIncludePatterns with multiple filters joins with the testBalloon pattern separator" {
            tool.buildIncludePatterns(
                listOf("fr.tokazio.okia.cli.TerminalOperationsTests", "fr.tokazio.okia.engine.MyTests"),
            ) shouldBe "fr.tokazio.okia.cli.TerminalOperationsTests⬥fr.tokazio.okia.engine.MyTests"
        }

        "buildIncludePatterns strips blank entries" {
            tool.buildIncludePatterns(listOf("", "  ", "fr.tokazio.okia.cli.TerminalOperationsTests")) shouldBe
                "fr.tokazio.okia.cli.TerminalOperationsTests"
        }

        "buildIncludePatterns with only blanks returns null" {
            tool.buildIncludePatterns(listOf("", "  ")) shouldBe null
        }

        "buildIncludePatterns merges testBalloon filters of several tasks" {
            val tasks =
                listOf(
                    TestTask("a", listOf("pkg.a.suiteA↘*", "pkg.A*")),
                    TestTask("b", listOf("pkg.b.suiteB↘*")),
                )

            tool.buildIncludePatterns(tool.testBalloonFiltersOf(tasks)) shouldBe "pkg.a.suiteA↘*⬥pkg.b.suiteB↘*"
        }

        // --- tool contract ---

        "runTests returns ERROR mentioning targets when mixed with legacy params" {
            val result = tool.runTests(listOf("pkg.A*"), null, null, listOf(TestTarget("a")), timeoutSeconds = 60)

            result shouldStartWith "ERROR"
            result shouldContain "targets"
        }

        "tool schema exposes targets and accepts them as JSON" {
            tool.name shouldBe "gradle_tests"
            tool.inputSchema shouldContain "targets"
            tool.executeWithJson(
                """{"testFilter":["pkg.A*"],"targets":[{"subproject":"a","testFilter":["pkg.A*"]}],"timeoutSeconds":60}""",
                ctx,
            ).output shouldContain "targets"
        }

        "tool schema marks timeoutSeconds as required so the agent must always provide it" {
            tool.inputSchema shouldContain "timeoutSeconds"
            tool.inputSchema shouldMatch Regex("""(?s).*"required"\s*:\s*\[[^\]]*"timeoutSeconds".*""")
        }

        "tool schema describes targets as an array of objects requiring a subproject" {
            val schema = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(tool.inputSchema)
            val targets = schema.get("properties").get("targets")

            targets.get("type").asText() shouldBe "array"
            targets.get("items").get("type").asText() shouldBe "object"
            targets.get("items").get("required").map { it.asText() } shouldBe listOf("subproject")
            targets.get("items").get("properties").get("testFilter").get("type").asText() shouldBe "array"
            schema.get("additionalProperties").asBoolean() shouldBe false
        }

        "a missing timeoutSeconds in the input answers a GRADLE_ERROR naming timeoutSeconds" {
            val result = tool.executeWithJson("""{"subproject":"cli"}""", ctx)

            result.success shouldBe false
            result.errorType shouldBe "GRADLE_ERROR"
            result.output shouldStartWith "ERROR"
            result.output shouldContain "timeoutSeconds"
        }

        "a name is prefixed with the config name when there is one" {
            GradleTestTool(GradleRunner(File("/fake")), configName = "MY_GRADLE").name shouldBe "MY_GRADLE__gradle_tests"
        }

        // --- timeoutSeconds ---

        "runTests times out after timeoutSeconds, says so and keeps the partial output" {
            val root = Files.createTempDirectory("agentos-test-timeout").toFile()
            try {
                root.resolve("gradlew").apply {
                    writeText("#!/bin/sh\necho partial output\nsleep 60\n")
                    setExecutable(true)
                }

                val result = toolIn(root).runTests(null, timeoutSeconds = 1)

                result shouldStartWith "ERROR"
                result shouldContain "timed out after 1 seconds"
                result shouldContain "partial output"
            } finally {
                root.deleteRecursively()
            }
        }

        "runTests rejects a timeoutSeconds outside 1..300 before doing anything else" {
            listOf(0, -5, 301).forEach { timeoutSeconds ->
                // /fake has no gradlew: only the timeout check can produce a message naming timeoutSeconds
                val result = tool.runTests(null, timeoutSeconds = timeoutSeconds)

                result shouldStartWith "ERROR"
                result shouldContain "timeoutSeconds"
                result shouldContain "300"
            }
        }

        "runTests accepts the maximum timeoutSeconds of 300" {
            val root = Files.createTempDirectory("agentos-test-max-timeout").toFile()
            try {
                root.resolve("gradlew").apply {
                    writeText("#!/bin/sh\nexit 0\n")
                    setExecutable(true)
                }

                toolIn(root).runTests(null, timeoutSeconds = 300) shouldStartWith "SUCCESS"
            } finally {
                root.deleteRecursively()
            }
        }

        // --- interruption ---

        "runTests returns an interrupted ERROR and kills gradle when the calling thread is interrupted" {
            val root = Files.createTempDirectory("agentos-test-interrupt").toFile()
            try {
                root.resolve("gradlew").apply {
                    writeText("#!/bin/sh\nsleep 60\n")
                    setExecutable(true)
                }
                var result: String? = null
                val thread = Thread.ofPlatform().start { result = GradleTestTool(root).runTests(null, timeoutSeconds = 60) }
                Thread.sleep(500)

                thread.interrupt()
                thread.join(5_000)

                thread.isAlive shouldBe false
                result!! shouldStartWith "ERROR"
                result!! shouldContain "interrupted"
            } finally {
                root.deleteRecursively()
            }
        }

        // --- init script: forces JUnit XML into the tool-owned dir without touching the project ---

        "runTests gives gradle an init script forcing junit xml, then deletes it" {
            val root = Files.createTempDirectory("agentos-test-init-script").toFile()
            try {
                root.resolve("gradlew").apply {
                    writeText(
                        "#!/bin/sh\nif [ \"\$1\" != \"-I\" ]; then exit 3; fi\n" +
                            "echo \"\$2\" > '${root.path}/init-path'\ncp \"\$2\" '${root.path}/init-copy'\n",
                    )
                    setExecutable(true)
                }

                toolIn(root).runTests(null, timeoutSeconds = 60)

                val content = root.resolve("init-copy").readText()
                content shouldContain "junitXml.required.set(true)"
                content shouldContain "agentos-test-results/$RUN_ID"
                File(root.resolve("init-path").readText().trim()).exists() shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        // --- each call reads only its own report dir ---

        "runTests reads the reports of its own run and ignores those of another run" {
            val root = Files.createTempDirectory("agentos-test-own-run").toFile()
            try {
                root.resolve("gradlew").apply {
                    writeText(
                        "#!/bin/sh\nmkdir -p '${root.path}/build/agentos-test-results/$RUN_ID/test'\n" +
                            "cat > '${root.path}/build/agentos-test-results/$RUN_ID/test/TEST-fresh.xml' <<'EOF'\n$REPORT_XML\nEOF\n",
                    )
                    setExecutable(true)
                }
                writeReport(root, runId = "other-run")

                val result = toolIn(root).runTests(null, timeoutSeconds = 60)

                result shouldContain "Tests: 1"
                root.resolve("build/agentos-test-results/other-run").exists() shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }

        "runTests deletes its report dir once the summary is built" {
            val root = Files.createTempDirectory("agentos-test-cleanup").toFile()
            try {
                root.resolve("gradlew").apply {
                    writeText(
                        "#!/bin/sh\nmkdir -p '${root.path}/build/agentos-test-results/$RUN_ID/test'\n" +
                            "cat > '${root.path}/build/agentos-test-results/$RUN_ID/test/TEST-fresh.xml' <<'EOF'\n$REPORT_XML\nEOF\n",
                    )
                    setExecutable(true)
                }

                toolIn(root).runTests(null, timeoutSeconds = 60)

                root.resolve("build/agentos-test-results/$RUN_ID").exists() shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        "runTests says no test reports found when gradle succeeds and wrote none, even if another run left some" {
            val root = Files.createTempDirectory("agentos-test-none").toFile()
            try {
                root.resolve("gradlew").apply {
                    writeText("#!/bin/sh\nexit 0\n")
                    setExecutable(true)
                }
                writeReport(root, runId = "other-run")

                val result = toolIn(root).runTests(null, timeoutSeconds = 60)

                result shouldContain "no test reports found"
                result shouldNotContain "Tests: 1"
            } finally {
                root.deleteRecursively()
            }
        }

        "runTests does not show another run's reports when gradle fails" {
            val root = Files.createTempDirectory("agentos-test-stale-fail").toFile()
            try {
                root.resolve("gradlew").apply {
                    writeText("#!/bin/sh\nexit 1\n")
                    setExecutable(true)
                }
                writeReport(root, runId = "other-run")

                val result = toolIn(root).runTests(null, timeoutSeconds = 60)

                result shouldContain "FAILURE"
                result shouldContain "no test reports found"
            } finally {
                root.deleteRecursively()
            }
        }

        "runTests deletes its report dir even when gradle times out" {
            val root = Files.createTempDirectory("agentos-test-cleanup-timeout").toFile()
            try {
                root.resolve("gradlew").apply {
                    writeText("#!/bin/sh\nmkdir -p '${root.path}/build/agentos-test-results/$RUN_ID/test'\nsleep 60\n")
                    setExecutable(true)
                }

                toolIn(root).runTests(null, timeoutSeconds = 1) shouldStartWith "ERROR"

                root.resolve("build/agentos-test-results/$RUN_ID").exists() shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        // --- gradlew not found ---

        "runTests returns an actionable ERROR when no gradlew can be found" {
            val root = Files.createTempDirectory("agentos-test-no-gradlew").toFile()
            try {
                val result = GradleTestTool(root).runTests(null, timeoutSeconds = 60)

                result shouldStartWith "ERROR"
                result shouldContain "gradlewPath"
            } finally {
                root.deleteRecursively()
            }
        }
    })
