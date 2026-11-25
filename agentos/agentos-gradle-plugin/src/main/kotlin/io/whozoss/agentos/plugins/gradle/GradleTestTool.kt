package io.whozoss.agentos.plugins.gradle

import io.whozoss.agentos.sdk.tool.BlockingTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import mu.KLogging
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Tool that runs tests via `./gradlew test` inside the project directory.
 *
 * The agent can invoke:
 * - [runTests] — run all tests (or a specific pattern)
 *
 * Process execution is delegated to [GradleRunner].
 *
 * The business logic ([runTests]) is blocking and returns a [GradleToolResult];
 * [executeBlocking] maps it to a [ToolExecutionResult] (`GRADLE_ERROR` / `GRADLE_FAILURE` error types).
 */
class GradleTestTool(
    private val runner: GradleRunner,
    private val newReportsDir: () -> File = TestReportsInitScript::newReportsDir,
    configName: String? = null,
) : BlockingTool<GradleTestTool.Input>() {
    private val parser = XmlTestReportParser()

    /** Input of the tool, as sent by the LLM. Nullable fields with defaults so partial JSON deserializes. */
    data class Input(
        val testFilter: List<String>? = null,
        val subproject: String? = null,
        val targets: List<TestTarget>? = null,
        val timeoutSeconds: Int? = null,
    )

    override val name: String = prefixedToolName(configName, TOOL_NAME)

    override val description: String =
        """Run tests in the project using Gradle (JUnit 5, Kotest, etc.).
Pass an optional Gradle subproject to narrow execution to a single module (avoids running all modules). Without it the root build runs; on a composite build (includeBuild), pass 'subproject' to target one of its included builds.
${GradleRunner.SUBPROJECT_NAME_HINT}
'timeoutSeconds' is mandatory (1 to ${ToolTimeout.MAX_SECONDS}): the run is killed when it is exceeded.
Pass an optional list of test filters.
To test several modules in ONE Gradle invocation (configuration paid once), pass 'targets' instead of
'testFilter'/'subproject': each target is {subproject, testFilter} and its filters apply to its module only.
'targets' cannot be combined with 'testFilter' or 'subproject'.
A 'testFilter' without 'subproject' on a multi-module build is applied by Gradle to every module, and a module with no
matching test can fail the run: to filter the tests of one or several modules, use 'subproject' or 'targets'.

Filter syntax:
  - Use fully-qualified class name: 'fr.tokazio.okia.cli.TerminalOperationsTests'
  - Use wildcard pattern:           'fr.tokazio.okia.cli.*' or '*ControllerSpec' (ONE wildcard filter, not one filter per class)
  - Leave testFilter null to run all tests.
  These filters are passed as Gradle '--tests' arguments.

Returns a structured summary: total/passed/failed counts,
and for each failure: class, method, exception type, message, relevant stacktrace frames and the root cause
(the last 'Caused by:' with its frames). Nothing more exists to find: no hidden detail in build/ files.
This result is the ONLY source of truth. It is complete: NEVER look for test results elsewhere
(build/ directory, XML or HTML reports, logs): this tool leaves no report behind, anything you find there is stale.
To check again, re-run this tool."""

    override val version: String = "1.0.0"

    override val paramType: Class<Input> = Input::class.java

    override val inputSchema: String =
        ToolSchema.schema(
            required = listOf("timeoutSeconds"),
            properties =
                mapOf(
                    "testFilter" to
                        ToolSchema.stringArray(
                            "Optional test filters (Gradle '--tests' syntax): fully-qualified class name or wildcard, " +
                                "e.g. 'fr.tokazio.okia.cli.TerminalOperationsTests' or 'fr.tokazio.okia.cli.*'. " +
                                "Leave null to run all tests. Without 'subproject', Gradle applies the filters to every " +
                                "module of a multi-module build, and a module with no match can fail the run: " +
                                "prefer 'subproject' or 'targets'.",
                        ),
                    "subproject" to
                        ToolSchema.string(
                            "Optional Gradle subproject name. ${GradleRunner.SUBPROJECT_NAME_HINT} " +
                                "When provided, only that module's tests are executed via ':<subproject>:test'. " +
                                "Leave blank or null to run the tests of the root build.",
                        ),
                    "targets" to
                        ToolSchema.objectArray(
                            description =
                                "Optional list of {subproject, testFilter} objects, run in a single Gradle invocation. " +
                                    "'subproject' is required. ${GradleRunner.SUBPROJECT_NAME_HINT} 'testFilter' is optional and uses the same " +
                                    "syntax as the top-level testFilter, applied to that subproject only. " +
                                    "Do not combine with top-level testFilter or subproject.",
                            itemRequired = listOf("subproject"),
                            itemProperties =
                                mapOf(
                                    "subproject" to
                                        ToolSchema.string(
                                            "Gradle subproject name of the module to test. ${GradleRunner.SUBPROJECT_NAME_HINT}",
                                        ),
                                    "testFilter" to
                                        ToolSchema.stringArray(
                                            "Optional test filters for this module only, same syntax as the top-level testFilter. " +
                                                "Leave null to run all its tests.",
                                        ),
                                ),
                        ),
                    "timeoutSeconds" to
                        ToolSchema.integer(
                            ToolTimeout.PARAM_DESCRIPTION +
                                "Size it to the scope: about 60 for one suite, 180 for one module, " +
                                "${ToolTimeout.MAX_SECONDS} for the whole project.",
                        ),
                ),
        )

    override fun executeBlocking(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val params = input ?: return missingTimeoutResult()
        val timeoutSeconds = params.timeoutSeconds ?: return missingTimeoutResult()
        return runTests(
            testFilter = params.testFilter,
            subproject = params.subproject,
            targets = params.targets,
            timeoutSeconds = timeoutSeconds,
        ).toToolExecutionResult()
    }

    fun runTests(
        testFilter: List<String>?,
        subproject: String? = null,
        targets: List<TestTarget>? = null,
        timeoutSeconds: Int,
    ): GradleToolResult {
        ToolTimeout.validate(timeoutSeconds)?.let { return GradleToolResult.Error(it) }
        val tasks =
            try {
                resolveTasks(testFilter, subproject, targets)
            } catch (e: IllegalArgumentException) {
                return GradleToolResult.Error("${e.message}")
            }
        val gradlew =
            try {
                runner.resolveGradlew()
            } catch (e: GradlewNotFoundException) {
                return GradleToolResult.Error("${e.message}")
            }
        val reportsDir = newReportsDir()
        val initScript = TestReportsInitScript.create(reportsDir)
        val command = buildCommand(gradlew, initScript.absolutePath, tasks)
        logger.info {
            "[GradleTestTool] projectDir='${runner.projectDir}' " +
                "tasks='${tasks.joinToString { "${it.subproject ?: "<all>"}${it.filters}" }}' " +
                "command='${command.joinToString(" ")}'"
        }

        return try {
            val result = runner.run(command, workDir = runner.workDirFor(gradlew), timeout = timeoutSeconds.seconds)

            if (result.timedOut) {
                return GradleToolResult.Error(
                    "Test execution timed out after $timeoutSeconds seconds. " +
                        "Retry with a larger timeoutSeconds (max ${ToolTimeout.MAX_SECONDS}) or a narrower scope.\n\n${result.rawOutput.keepEnd()}",
                )
            }

            val hint = runner.compositeBuildHint(result.exitCode, tasks.any { it.subproject != null }, gradlew, result.rawOutput)
            val xmlReports = parser.collect(reportsDir)
            logger.info { "[GradleTestTool] xml reports found: ${xmlReports.size}" }

            val text =
                if (xmlReports.isEmpty()) {
                    logger.warn { "[GradleTestTool] no xml reports generated — exit=${result.exitCode}" }
                    val status = if (result.exitCode == 0) "SUCCESS" else "FAILURE"
                    "$status (exit ${result.exitCode}) — no test reports found\n\n${result.rawOutput.keepEnd()}"
                } else {
                    parser.buildSummary(result.exitCode, xmlReports, result.rawOutput)
                }
            GradleToolResult.ofExit(result.exitCode, if (hint == null) text else "$text\n\n$hint")
        } catch (e: InterruptedException) {
            // GradleRunner already killed the process tree and re-set the interrupt flag
            logger.warn { "[GradleTestTool] interrupted" }
            GradleToolResult.Error("Test execution interrupted.")
        } catch (e: Exception) {
            logger.error(e) { "Failed to run tests" }
            GradleToolResult.Error("${e.message}")
        } finally {
            initScript.delete()
            parser.delete(reportsDir)
        }
    }

    /**
     * Turns the tool params into tasks. Legacy `testFilter`/`subproject` give one task; `targets` give one per target.
     *
     * @throws IllegalArgumentException when `targets` is mixed with legacy params or a target has no subproject.
     */
    internal fun resolveTasks(
        testFilter: List<String>?,
        subproject: String?,
        targets: List<TestTarget>?,
    ): List<TestTask> {
        if (targets.isNullOrEmpty()) {
            return listOf(TestTask(subproject?.takeIf { it.isNotBlank() }, nonBlank(testFilter)))
        }
        require(testFilter.isNullOrEmpty() && subproject.isNullOrBlank()) {
            "'targets' cannot be combined with 'testFilter' or 'subproject'. Put the filters in each target."
        }
        return targets.map { target ->
            require(target.subproject.isNotBlank()) { "Each target needs a non-blank 'subproject'." }
            TestTask(target.subproject, nonBlank(target.testFilter))
        }
    }

    /**
     * All tasks go in one Gradle call, so global options come first and each `--tests=<filter>` follows its own task.
     * The glued `--option=value` form keeps a filter starting with `-` from being read as an option.
     * [initScript] redirects the JUnit XML reports to the tool-owned dir (see [TestReportsInitScript]).
     */
    internal fun buildCommand(
        gradlew: String,
        initScript: String,
        tasks: List<TestTask>,
    ): List<String> {
        val command = mutableListOf(gradlew, "-I", initScript, "--continue", "--quiet", "--console=plain")
        tasks.forEach { task ->
            command += task.subproject?.let { ":${it.trimStart(':')}:test" } ?: "test"
            task.filters.forEach { command += "--tests=$it" }
        }
        return command
    }

    private fun nonBlank(filters: List<String>?): List<String> = filters.orEmpty().filter { it.isNotBlank() }

    companion object : KLogging() {
        const val TOOL_NAME = "gradle_tests"
    }
}
