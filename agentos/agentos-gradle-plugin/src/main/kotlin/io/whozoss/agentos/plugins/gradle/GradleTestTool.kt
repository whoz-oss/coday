package io.whozoss.agentos.plugins.gradle

import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import mu.KLogging
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Tool that runs tests via `./gradlew test` inside the project directory.
 *
 * The agent can invoke:
 * - [runTests] — run all tests (or a specific pattern)
 *
 * Process execution is delegated to [GradleRunner].
 *
 * The business logic ([runTests]) is blocking and returns a String starting with `ERROR` / `FAILURE` on problems;
 * [execute] maps it to a [ToolExecutionResult] (`GRADLE_ERROR` / `GRADLE_FAILURE` error types).
 */
class GradleTestTool(
    private val runner: GradleRunner,
    private val newRunId: () -> String = { UUID.randomUUID().toString() },
    configName: String? = null,
) : StandardTool<GradleTestTool.Input> {
    private val parser = XmlTestReportParser()

    /**
     * Secondary constructor kept for backward compatibility with tests that pass a [File] directly.
     */
    constructor(projectDir: File) : this(GradleRunner(projectDir))

    /** Input of the tool, as sent by the LLM. Nullable fields with defaults so partial JSON deserializes. */
    data class Input(
        val testFilter: List<String>? = null,
        val subproject: String? = null,
        val targets: List<TestTarget>? = null,
        val timeoutSeconds: Int? = null,
    )

    override val name: String = prefixedToolName(configName, TOOL_NAME)

    override val description: String =
        """Run tests in the project using Gradle. Supports multiple test frameworks.
Pass an optional Gradle subproject to narrow execution to a single module (avoids running all modules). Without it the root build runs; on a composite build (includeBuild), pass 'subproject' to target one of its included builds.
${GradleRunner.SUBPROJECT_NAME_HINT}
'timeoutSeconds' is mandatory (1 to ${ToolTimeout.MAX_SECONDS}): the run is killed when it is exceeded.
Pass an optional list of test filters; the tool auto-detects the framework from the filter syntax.
To test several modules in ONE Gradle invocation (configuration paid once), pass 'targets' instead of
'testFilter'/'subproject': each target is {subproject, testFilter} and its filters apply to its module only.
'targets' cannot be combined with 'testFilter' or 'subproject'.
With testBalloon filters, use fully-qualified suite names so they only match in their own module.

Standard filter syntax (JUnit5, Kotest, etc.):
  - Use fully-qualified class name: 'fr.tokazio.okia.cli.TerminalOperationsTests'
  - Use wildcard pattern:           'fr.tokazio.okia.cli.*' or '*ControllerSpec' (ONE wildcard filter, not one filter per class)
  - Leave testFilter null to run all tests.
  These filters are passed as Gradle '--tests' arguments.

testBalloon filter syntax (only for testBalloon suites, detected by '↘' separator):
  - The suite name is the Kotlin 'val' property name, which starts with a LOWERCASE letter.
    Example: a file 'TerminalOperationsTests.kt' with 'val terminalOperationsTests = ...' → suite name is 'terminalOperationsTests'.
  - Tests within a suite are separated from the suite name by '↘' (U+2198, SOUTH EAST ARROW).
  - To run all tests in a suite : 'fr.tokazio.okia.cli.terminalOperationsTests↘*'
  - To run a specific test      : 'fr.tokazio.okia.cli.terminalOperationsTests↘my test name'
  IMPORTANT: do NOT add a 'Kt' suffix. Use 'terminalOperationsTests' not 'TerminalOperationsTests' or 'TerminalOperationsTestsKt'.
  These filters are passed via TESTBALLOON_INCLUDE_PATTERNS env var.

You may mix both syntaxes in the same testFilter list.
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
                            "Optional test filters. Framework is auto-detected from syntax. " +
                                "Standard (JUnit5/Kotest): fully-qualified class name or wildcard, " +
                                "e.g. 'fr.tokazio.okia.cli.TerminalOperationsTests'. " +
                                "testBalloon: suite name (lowercase Kotlin val) + '↘' separator + test name or '*', " +
                                "e.g. 'fr.tokazio.okia.cli.terminalOperationsTests↘*'. " +
                                "Do NOT use 'Kt' suffix for testBalloon suites. Leave null to run all tests.",
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

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val params = input ?: return missingTimeoutResult()
        val timeoutSeconds = params.timeoutSeconds ?: return missingTimeoutResult()
        return runInterruptibleTool {
            runTests(
                testFilter = params.testFilter,
                subproject = params.subproject,
                targets = params.targets,
                timeoutSeconds = timeoutSeconds,
            )
        }
    }

    fun runTests(
        testFilter: List<String>?,
        subproject: String? = null,
        targets: List<TestTarget>? = null,
        timeoutSeconds: Int,
    ): String {
        ToolTimeout.validate(timeoutSeconds)?.let { return "ERROR: $it" }
        val tasks =
            try {
                resolveTasks(testFilter, subproject, targets)
            } catch (e: IllegalArgumentException) {
                return "ERROR: ${e.message}"
            }
        val gradlew =
            try {
                runner.resolveGradlew()
            } catch (e: GradlewNotFoundException) {
                return "ERROR: ${e.message}"
            }
        val testBalloonFilters = testBalloonFiltersOf(tasks)
        val runId = newRunId()
        val initScript = TestReportsInitScript.create(runId)
        val command = buildCommand(gradlew, initScript.absolutePath, tasks, testBalloonActive = testBalloonFilters.isNotEmpty())
        val includePatterns = buildIncludePatterns(testBalloonFilters)
        logger.info {
            "[GradleTestTool] projectDir='${runner.projectDir}' " +
                "tasks='${tasks.joinToString { "${it.subproject ?: "<all>"}${it.filters}" }}' " +
                "testBalloonFilters='${testBalloonFilters.ifEmpty { listOf("<none>") }}' " +
                "TESTBALLOON_INCLUDE_PATTERNS='${includePatterns ?: "<unset>"}' " +
                "command='${command.joinToString(" ")}'"
        }

        return try {
            val result =
                runner.run(
                    command,
                    workDir = runner.workDirFor(gradlew),
                    timeout = timeoutSeconds.seconds,
                    extraEnv =
                        buildMap {
                            if (includePatterns != null) {
                                put(
                                    "TESTBALLOON_INCLUDE_PATTERNS",
                                    includePatterns,
                                )
                            }
                        },
                )

            if (result.timedOut) {
                return "ERROR: Test execution timed out after $timeoutSeconds seconds. " +
                    "Retry with a larger timeoutSeconds (max ${ToolTimeout.MAX_SECONDS}) or a narrower scope.\n\n${result.rawOutput}"
            }

            val xmlReports = parser.collect(runner.gradleDir, runId)
            logger.info { "[GradleTestTool] xml reports found: ${xmlReports.size}" }

            if (xmlReports.isEmpty()) {
                logger.warn { "[GradleTestTool] no xml reports generated — exit=${result.exitCode}" }
                return if (result.exitCode == 0) {
                    "SUCCESS (exit ${result.exitCode}) — no test reports found\n\n${result.rawOutput}"
                } else {
                    val hint = runner.compositeBuildHint(result.exitCode, tasks.any { it.subproject != null }, gradlew)
                    "FAILURE (exit ${result.exitCode}) — no test reports found\n\n${result.rawOutput}" +
                        (hint?.let { "\n\n$it" } ?: "")
                }
            }

            parser.buildSummary(result.exitCode, xmlReports)
        } catch (e: InterruptedException) {
            // GradleRunner already killed the process tree and re-set the interrupt flag
            logger.warn { "[GradleTestTool] interrupted" }
            "ERROR: Test execution interrupted."
        } catch (e: Exception) {
            logger.error(e) { "Failed to run tests" }
            "ERROR: ${e.message}"
        } finally {
            initScript.delete()
            parser.delete(runner.gradleDir, runId)
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
     * All tasks go in one Gradle call, so global options come first and each `--tests` follows its own task.
     * [initScript] redirects the JUnit XML reports to the tool-owned dir (see [TestReportsInitScript]).
     */
    internal fun buildCommand(
        gradlew: String,
        initScript: String,
        tasks: List<TestTask>,
        testBalloonActive: Boolean = true,
    ): List<String> {
        val command = mutableListOf(gradlew, "-I", initScript, "--continue", "--quiet", "--console=plain")
        if (testBalloonActive) {
            command += "-PtestBalloon.jvmTestBalloonTestsOnly=true"
        }
        tasks.forEach { task ->
            command += task.subproject?.let { ":${it.trimStart(':')}:test" } ?: "test"
            task.filters.filterNot(::isTestBalloonFilter).forEach { command += listOf("--tests", it) }
        }
        return command
    }

    /** testBalloon reads one env var for the whole Gradle process, so patterns of all tasks are merged. */
    internal fun testBalloonFiltersOf(tasks: List<TestTask>): List<String> = tasks.flatMap { it.filters }.filter(::isTestBalloonFilter)

    private fun isTestBalloonFilter(filter: String): Boolean = filter.contains('↘')

    private fun nonBlank(filters: List<String>?): List<String> = filters.orEmpty().filter { it.isNotBlank() }

    /** testBalloon splits `TESTBALLOON_INCLUDE_PATTERNS` on [TESTBALLOON_PATTERN_SEPARATOR]; a newline is read as part of a pattern. */
    internal fun buildIncludePatterns(filters: List<String>): String? =
        filters.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(TESTBALLOON_PATTERN_SEPARATOR.toString())

    companion object : KLogging() {
        const val TOOL_NAME = "gradle_tests"

        /** testBalloon `Constants.INTERNAL_PATH_PATTERN_SEPARATOR` (U+2B25). */
        private const val TESTBALLOON_PATTERN_SEPARATOR = '⬥'
    }
}
