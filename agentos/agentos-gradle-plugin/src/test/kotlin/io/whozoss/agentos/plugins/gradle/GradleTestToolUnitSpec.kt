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

/** A tool whose reports directory is [reportsDir], so that a fake `gradlew` knows where to write its reports. */
private fun toolIn(
    root: File,
    reportsDir: File,
) = GradleTestTool(GradleRunner(root), newReportsDir = { reportsDir })

/** Writes an executable fake `gradlew` running [body] into [root]. */
private fun fakeGradlew(
    root: File,
    body: String,
) {
    root.resolve("gradlew").apply {
        writeText("#!/bin/sh\n$body\n")
        setExecutable(true)
    }
}

/** Shell snippet writing [REPORT_XML] as a report of [module] under [reportsDir], as the init script would. */
private fun writesReport(
    reportsDir: File,
    module: String = "root-1a2b",
) = "mkdir -p '${reportsDir.path}/$module/test'\n" +
    "cat > '${reportsDir.path}/$module/test/TEST-fresh.xml' <<'EOF'\n$REPORT_XML\nEOF"

/** Runs [block] with a project root and a separate reports directory, both deleted afterwards. */
private fun withDirs(block: (root: File, reportsDir: File) -> Unit) {
    val root = Files.createTempDirectory("agentos-test-root").toFile()
    val reportsDir = Files.createTempDirectory("agentos-test-reports").toFile()
    try {
        block(root, reportsDir)
    } finally {
        root.deleteRecursively()
        reportsDir.deleteRecursively()
    }
}

class GradleTestToolUnitSpec :
    StringSpec({

        val tool = GradleTestTool(GradleRunner(File("/fake")))
        val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())

        // --- buildCommand ---

        "buildCommand with no subproject runs plain test task" {
            val cmd = tool.buildCommand(gradlew = "/fake/gradlew", initScript = INIT_SCRIPT, tasks = listOf(TestTask(subproject = null)))

            cmd shouldContainInOrder listOf("/fake/gradlew", "test")
            cmd.none { it.startsWith("--tests") } shouldBe true
        }

        "buildCommand with subproject targets subproject test task" {
            val cmd = tool.buildCommand(gradlew = "/fake/gradlew", initScript = INIT_SCRIPT, tasks = listOf(TestTask(subproject = "cli")))

            cmd shouldContainInOrder listOf("/fake/gradlew", ":cli:test")
            cmd shouldNotContain "test" // bare 'test' task must not appear
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
                    "--tests=pkg.A*",
                    "--tests=pkg.B*",
                    ":b:test",
                    "--tests=pkg.C*",
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
                )

            cmd shouldContainInOrder listOf(":a:test", "--tests=pkg.A*", ":b:test")
            cmd.last() shouldBe ":b:test"
        }

        "buildCommand glues a filter starting with a dash to --tests so Gradle cannot read it as an option" {
            val cmd =
                tool.buildCommand(
                    gradlew = "/fake/gradlew",
                    initScript = INIT_SCRIPT,
                    tasks = listOf(TestTask("a", listOf("-Dweird"))),
                )

            cmd shouldContainInOrder listOf(":a:test", "--tests=-Dweird")
            cmd.none { it == "-Dweird" } shouldBe true
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

        // --- tool contract ---

        "runTests returns ERROR mentioning targets when mixed with legacy params" {
            val result = tool.runTests(listOf("pkg.A*"), null, listOf(TestTarget("a")), timeoutSeconds = 60).errorText()

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

                val result = GradleTestTool(GradleRunner(root)).runTests(null, timeoutSeconds = 1).errorText()

                result shouldContain "timed out after 1 seconds"
                result shouldContain "partial output"
            } finally {
                root.deleteRecursively()
            }
        }

        "runTests rejects a timeoutSeconds outside 1..240 before doing anything else" {
            listOf(0, -5, 241).forEach { timeoutSeconds ->
                // /fake has no gradlew: only the timeout check can produce a message naming timeoutSeconds
                val result = tool.runTests(null, timeoutSeconds = timeoutSeconds).errorText()

                result shouldContain "timeoutSeconds"
                result shouldContain "240"
            }
        }

        "runTests accepts the maximum timeoutSeconds of 240" {
            val root = Files.createTempDirectory("agentos-test-max-timeout").toFile()
            try {
                root.resolve("gradlew").apply {
                    writeText("#!/bin/sh\nexit 0\n")
                    setExecutable(true)
                }

                GradleTestTool(GradleRunner(root)).runTests(null, timeoutSeconds = 240).successText()
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
                var result: GradleToolResult? = null
                val thread = Thread.ofPlatform().start { result = GradleTestTool(GradleRunner(root)).runTests(null, timeoutSeconds = 60) }
                Thread.sleep(500)

                thread.interrupt()
                thread.join(5_000)

                thread.isAlive shouldBe false
                result!!.errorText() shouldContain "interrupted"
            } finally {
                root.deleteRecursively()
            }
        }

        // --- init script: forces JUnit XML into the tool-owned dir without touching the project ---

        "runTests gives gradle an init script forcing junit xml into the reports dir, then deletes it" {
            withDirs { root, reportsDir ->
                fakeGradlew(
                    root,
                    "if [ \"\$1\" != \"-I\" ]; then exit 3; fi\n" +
                        "echo \"\$2\" > '${root.path}/init-path'\ncp \"\$2\" '${root.path}/init-copy'",
                )

                toolIn(root, reportsDir).runTests(null, timeoutSeconds = 60)

                val content = root.resolve("init-copy").readText()
                content shouldContain "junitXml.required.set(true)"
                content shouldContain reportsDir.absolutePath
                File(root.resolve("init-path").readText().trim()).exists() shouldBe false
            }
        }

        "the init script escapes a quote or a backslash of the reports dir path" {
            val dir = Files.createTempDirectory("agentos-init-escape").toFile()
            val script = TestReportsInitScript.create(dir.resolve("it's a \\dir"))
            try {
                script.readText() shouldContain "new File('${dir.absolutePath.replace("\\", "\\\\")}/it\\'s a \\\\dir'"
            } finally {
                script.delete()
                dir.deleteRecursively()
            }
        }

        // --- each call reads only its own reports dir, wherever the build lives ---

        "runTests reads the reports of its own reports dir" {
            withDirs { root, reportsDir ->
                fakeGradlew(root, writesReport(reportsDir))

                val result = toolIn(root, reportsDir).runTests(null, timeoutSeconds = 60).successText()

                result shouldContain "Tests: 1"
            }
        }

        "runTests reads the reports of an included build living outside the project directory" {
            withDirs { root, reportsDir ->
                // the reports dir is not under root, as the dir of an included build (includeBuild("../lib")) is not
                fakeGradlew(root, writesReport(reportsDir, module = "lib-9f8e") + "\n" + writesReport(reportsDir, module = "root-1a2b"))

                val result = toolIn(root, reportsDir).runTests(null, timeoutSeconds = 60).successText()

                result shouldContain "Tests: 2"
            }
        }

        "runTests deletes its reports dir once the summary is built" {
            withDirs { root, reportsDir ->
                fakeGradlew(root, writesReport(reportsDir))

                toolIn(root, reportsDir).runTests(null, timeoutSeconds = 60)

                reportsDir.exists() shouldBe false
            }
        }

        "runTests ignores the project own reports of build/test-results" {
            withDirs { root, reportsDir ->
                fakeGradlew(root, "mkdir -p '${root.path}/build/test-results/test'\necho '$REPORT_XML' > '${root.path}/build/test-results/test/TEST-old.xml'")

                val result = toolIn(root, reportsDir).runTests(null, timeoutSeconds = 60).successText()

                result shouldContain "no test reports found"
                result shouldNotContain "Tests: 1"
            }
        }

        "runTests says no test reports found when gradle succeeds and wrote none" {
            withDirs { root, reportsDir ->
                fakeGradlew(root, "exit 0")

                val result = toolIn(root, reportsDir).runTests(null, timeoutSeconds = 60).successText()

                result shouldContain "no test reports found"
            }
        }

        "runTests says no test reports found when gradle fails and wrote none" {
            withDirs { root, reportsDir ->
                fakeGradlew(root, "echo compilation exploded\nexit 1")

                val result = toolIn(root, reportsDir).runTests(null, timeoutSeconds = 60).failureText()

                result shouldContain "FAILURE"
                result shouldContain "no test reports found"
                result shouldContain "compilation exploded"
            }
        }

        // --- a failed build always says why ---

        "runTests gives the gradle output when the build fails while the reports hold no failed test" {
            withDirs { root, reportsDir ->
                fakeGradlew(root, writesReport(reportsDir) + "\necho 'No tests found for given includes'\nexit 1")

                val result = toolIn(root, reportsDir).runTests(null, timeoutSeconds = 60).failureText()

                result shouldStartWith "FAILURE"
                result shouldContain "Tests: 1"
                result shouldContain "No tests found for given includes"
            }
        }

        "runTests with targets gives the gradle output of the module that matched no test, next to the reports of the other" {
            withDirs { root, reportsDir ->
                fakeGradlew(
                    root,
                    writesReport(reportsDir, module = "a-1111") +
                        "\necho \"Execution failed for task ':b:test'. No tests found for given includes\"\nexit 1",
                )

                val result =
                    toolIn(root, reportsDir).runTests(
                        testFilter = null,
                        targets = listOf(TestTarget("a"), TestTarget("b", listOf("pkg.Nothing*"))),
                        timeoutSeconds = 60,
                    ).failureText()

                result shouldContain "Tests: 1"
                result shouldContain "No tests found for given includes"
            }
        }

        "runTests does not repeat the gradle output when the build succeeds" {
            withDirs { root, reportsDir ->
                fakeGradlew(root, writesReport(reportsDir) + "\necho some gradle noise")

                toolIn(root, reportsDir).runTests(null, timeoutSeconds = 60).successText() shouldNotContain "some gradle noise"
            }
        }

        "runTests deletes its reports dir even when gradle times out" {
            withDirs { root, reportsDir ->
                fakeGradlew(root, writesReport(reportsDir) + "\nsleep 60")

                toolIn(root, reportsDir).runTests(null, timeoutSeconds = 1).errorText()

                reportsDir.exists() shouldBe false
            }
        }

        // --- gradlew not found ---

        "runTests returns an actionable ERROR when no gradlew can be found" {
            val root = Files.createTempDirectory("agentos-test-no-gradlew").toFile()
            try {
                val result = GradleTestTool(GradleRunner(root)).runTests(null, timeoutSeconds = 60).errorText()

                result shouldContain "gradlewDirectory"
            } finally {
                root.deleteRecursively()
            }
        }
    })
