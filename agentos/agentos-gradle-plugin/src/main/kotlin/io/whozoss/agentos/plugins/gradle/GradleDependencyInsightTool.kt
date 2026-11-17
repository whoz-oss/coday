package io.whozoss.agentos.plugins.gradle

import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import mu.KLogging
import kotlin.time.Duration.Companion.seconds

/**
 * Tool that answers "why is this dependency on the classpath?" via `./gradlew dependencyInsight`.
 *
 * The raw Gradle report is condensed ([condense]) to the resolved version, the selection reasons and a single
 * path from the configuration to the dependency, so the agent never receives the whole dependency graph.
 *
 * The business logic ([dependencyInsight]) is blocking and returns a String starting with `ERROR` / `FAILURE` on
 * problems; [execute] maps it to a [ToolExecutionResult] (`GRADLE_ERROR` / `GRADLE_FAILURE` error types).
 */
class GradleDependencyInsightTool(
    private val runner: GradleRunner,
    configName: String? = null,
) : StandardTool<GradleDependencyInsightTool.Input> {
    /** Input of the tool, as sent by the LLM. Nullable fields with defaults so partial JSON deserializes. */
    data class Input(
        val dependency: String? = null,
        val subproject: String? = null,
        val configuration: String? = null,
        val gradlewPath: String? = null,
        val timeoutSeconds: Int? = null,
    )

    override val name: String = prefixedToolName(configName, TOOL_NAME)

    override val description: String =
        """Explain why a dependency is on a Gradle classpath, using the dependencyInsight task.
Returns the resolved version, the selection reasons and ONE path from the configuration to the dependency.
The full dependency graph is never returned.

Targeting:
  - 'dependency' is a group, a module name or 'group:name' substring (e.g. 'thymeleaf', 'org.thymeleaf:thymeleaf').
  - 'subproject' selects the Gradle module. ${GradleRunner.SUBPROJECT_NAME_HINT} Leave null for the root project.
  - 'configuration' defaults to '$DEFAULT_CONFIGURATION' (e.g. 'compileClasspath', 'testRuntimeClasspath').
'timeoutSeconds' is mandatory (1 to ${ToolTimeout.MAX_SECONDS}): the run is killed when it is exceeded.
${GradleRunner.GRADLEW_NOT_FOUND_HINT}

Returns: the condensed report (at most $MAX_LINES lines), or FAILURE (exit N) followed by the condensed Gradle output."""

    override val version: String = "1.0.0"

    override val paramType: Class<Input> = Input::class.java

    override val inputSchema: String =
        ToolSchema.schema(
            required = listOf("dependency", "timeoutSeconds"),
            properties =
                mapOf(
                    "dependency" to
                        ToolSchema.string(
                            "Dependency to look for: group, module name or 'group:name' substring. Required.",
                        ),
                    "subproject" to
                        ToolSchema.string(
                            "Optional Gradle subproject name. ${GradleRunner.SUBPROJECT_NAME_HINT} " +
                                "Leave null to inspect the root project.",
                        ),
                    "configuration" to
                        ToolSchema.string(
                            "Optional configuration to inspect. Defaults to '$DEFAULT_CONFIGURATION'.",
                        ),
                    "gradlewPath" to ToolSchema.string(GradleRunner.GRADLEW_PATH_PARAM_DESCRIPTION),
                    "timeoutSeconds" to
                        ToolSchema.integer(
                            ToolTimeout.PARAM_DESCRIPTION +
                                "Size it to the scope: about 60 for one module.",
                        ),
                ),
        )

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val params = input ?: return missingTimeoutResult()
        val timeoutSeconds = params.timeoutSeconds ?: return missingTimeoutResult()
        val dependency = params.dependency ?: return "ERROR: 'dependency' must not be blank.".toToolExecutionResult()
        return runInterruptibleTool {
            dependencyInsight(
                dependency = dependency,
                subproject = params.subproject,
                configuration = params.configuration,
                gradlewPath = params.gradlewPath,
                timeoutSeconds = timeoutSeconds,
            )
        }
    }

    fun dependencyInsight(
        dependency: String,
        subproject: String? = null,
        configuration: String? = null,
        gradlewPath: String? = null,
        timeoutSeconds: Int,
    ): String {
        ToolTimeout.validate(timeoutSeconds)?.let { return "ERROR: $it" }
        if (dependency.isBlank()) return "ERROR: 'dependency' must not be blank."

        val gradlew =
            try {
                runner.resolveGradlew(gradlewPath)
            } catch (e: GradlewNotFoundException) {
                return "ERROR: ${e.message}"
            }
        val command =
            buildCommand(
                gradlew = gradlew,
                dependency = dependency.trim(),
                subproject = subproject?.takeIf { it.isNotBlank() },
                configuration = configuration?.takeIf { it.isNotBlank() } ?: DEFAULT_CONFIGURATION,
            )
        logger.info { "[GradleDependencyInsightTool] dir='${runner.projectDir}' command='${command.joinToString(" ")}'" }

        return try {
            val result = runner.run(command, workDir = runner.workDirFor(gradlew), timeout = timeoutSeconds.seconds)
            if (result.timedOut) {
                return "ERROR: dependencyInsight timed out after $timeoutSeconds seconds. " +
                    "Retry with a larger timeoutSeconds (max ${ToolTimeout.MAX_SECONDS}).\n\n${condense(result.rawOutput)}"
            }
            val report = condense(result.rawOutput)
            if (result.exitCode == 0) report else "FAILURE (exit ${result.exitCode})\n$report"
        } catch (e: InterruptedException) {
            // GradleRunner already killed the process tree and re-set the interrupt flag
            logger.warn { "[GradleDependencyInsightTool] interrupted" }
            "ERROR: dependencyInsight interrupted."
        } catch (e: Exception) {
            logger.error(e) { "Failed to run dependencyInsight" }
            "ERROR: ${e.message}"
        }
    }

    // Options are glued to their value (`--opt=value`) so a value can never be parsed by Gradle as another option.
    internal fun buildCommand(
        gradlew: String,
        dependency: String,
        subproject: String?,
        configuration: String,
    ): List<String> {
        val task = subproject?.let { ":${it.trimStart(':')}:dependencyInsight" } ?: ":dependencyInsight"
        return listOf(
            gradlew,
            task,
            "--dependency=$dependency",
            "--configuration=$configuration",
            "--single-path",
            "--quiet",
            "--console=plain",
        )
    }

    /**
     * Keeps the resolved coordinates, the selection reasons and the reverse tree. Drops `Variant` blocks with their
     * attribute tables, blank lines, consecutive duplicates and the scan advert; caps at [MAX_LINES] lines.
     */
    internal fun condense(raw: String): String {
        val kept =
            raw
                .lines()
                .filterNot { it.isBlank() || VARIANT_LINE.matches(it) || TABLE_ROW.matches(it) || it.startsWith(SCAN_ADVERT) }
                .let { lines -> lines.filterIndexed { i, line -> i == 0 || line != lines[i - 1] } }
        if (kept.size <= MAX_LINES) return kept.joinToString("\n")
        return kept.take(MAX_LINES).joinToString("\n") + "\n[truncated: ${kept.size - MAX_LINES} more lines]"
    }

    companion object : KLogging() {
        const val TOOL_NAME = "gradle_dependency_insight"
        private const val DEFAULT_CONFIGURATION = "runtimeClasspath"
        private const val MAX_LINES = 60
        private const val SCAN_ADVERT = "A web-based, searchable dependency report"
        private val VARIANT_LINE = Regex("""^\s*Variant .*:$""")
        private val TABLE_ROW = Regex("""^\s*\|.*""")
    }
}
