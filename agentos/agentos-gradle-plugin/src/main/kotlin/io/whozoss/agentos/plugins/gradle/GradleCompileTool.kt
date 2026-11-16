package io.whozoss.agentos.plugins.gradle

import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import mu.KLogging
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Tool that compiles the project (main + test sources) via `./gradlew testClasses`.
 *
 * `testClasses` depends on `classes`, so it compiles both production and test code.
 * No tests are executed.
 *
 * Subproject resolution from a file path:
 *   When [file] is provided without [subproject], the tool walks up from the file's directory
 *   looking for the nearest `build.gradle.kts` / `build.gradle`. The Gradle module name is
 *   derived from that directory's name relative to the project root.
 *   If neither file nor subproject is given, the whole project is compiled.
 *
 * The business logic ([compile]) is blocking and returns a String starting with `ERROR` / `FAILURE` on problems;
 * [execute] maps it to a [ToolExecutionResult] (`GRADLE_ERROR` / `GRADLE_FAILURE` error types).
 */
class GradleCompileTool(
    private val runner: GradleRunner,
    configName: String? = null,
) : StandardTool<GradleCompileTool.Input> {
    private val diagnosticParser = CompilerDiagnosticParser()

    /** Input of the tool, as sent by the LLM. Nullable fields with defaults so partial JSON deserializes. */
    data class Input(
        val subproject: String? = null,
        val file: String? = null,
        val gradlewPath: String? = null,
        val timeoutSeconds: Int? = null,
    )

    override val name: String = prefixedToolName(configName, TOOL_NAME)

    override val description: String =
        """Compile the project (main + test sources) using Gradle's testClasses task.
No tests are run — compilation errors and warnings are reported directly.

Subproject and file targeting:
  - Pass 'subproject' to compile only that Gradle module. ${GradleRunner.SUBPROJECT_NAME_HINT}
  - Pass 'file' (absolute or project-relative path to a .kt / .java file) to auto-detect the module
    and filter diagnostics for that specific file. Diagnostics from the target file appear first;
    diagnostics from other files in the same module are listed separately.
  - If both 'subproject' and 'file' are given, 'subproject' is used for compilation scope
    and 'file' is used only for diagnostic filtering.
  - Leave both null to compile all modules.
'timeoutSeconds' is mandatory (1 to ${ToolTimeout.MAX_SECONDS}): the run is killed when it is exceeded.
${GradleRunner.GRADLEW_NOT_FOUND_HINT}

Returns: SUCCESS or FAILURE, error/warning counts, and structured diagnostics (file, line, column, message)."""

    override val version: String = "1.0.0"

    override val paramType: Class<Input> = Input::class.java

    override val inputSchema: String =
        ToolSchema.schema(
            required = listOf("timeoutSeconds"),
            properties =
                mapOf(
                    "subproject" to
                        ToolSchema.string(
                            "Optional Gradle subproject name. ${GradleRunner.SUBPROJECT_NAME_HINT} " +
                                "When provided, only that module is compiled via ':<subproject>:testClasses'. " +
                                "Takes priority over 'file' for compilation scope. Leave null to compile all modules.",
                        ),
                    "file" to
                        ToolSchema.string(
                            "Optional path to a source file (.kt or .java), absolute or relative to the project root. " +
                                "Used to auto-detect the owning Gradle module (if 'subproject' is absent) " +
                                "and to filter diagnostics so that the target file's errors appear first. " +
                                "Leave null when targeting a whole module or the whole project.",
                        ),
                    "gradlewPath" to ToolSchema.string(GradleRunner.GRADLEW_PATH_PARAM_DESCRIPTION),
                    "timeoutSeconds" to
                        ToolSchema.integer(
                            ToolTimeout.PARAM_DESCRIPTION +
                                "Size it to the scope: about 60 for one module, ${ToolTimeout.MAX_SECONDS} for the whole project.",
                        ),
                ),
        )

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val params = input ?: return missingTimeoutResult()
        val timeoutSeconds = params.timeoutSeconds ?: return missingTimeoutResult()
        return compile(
            subproject = params.subproject,
            file = params.file,
            gradlewPath = params.gradlewPath,
            timeoutSeconds = timeoutSeconds,
        ).toToolExecutionResult()
    }

    fun compile(
        subproject: String? = null,
        file: String? = null,
        gradlewPath: String? = null,
        timeoutSeconds: Int,
    ): String {
        ToolTimeout.validate(timeoutSeconds)?.let { return "ERROR: $it" }
        val resolvedSubproject = subproject?.takeIf { it.isNotBlank() }
        val resolvedFile = file?.takeIf { it.isNotBlank() }

        val (effectiveSubproject, fileFilter) =
            resolveTargets(resolvedSubproject, resolvedFile)
                ?: return "ERROR: Could not locate a Gradle module for file '$resolvedFile'. " +
                    "Ensure the file path is inside the project and belongs to a Gradle subproject."

        val gradlew =
            try {
                runner.resolveGradlew(gradlewPath)
            } catch (e: GradlewNotFoundException) {
                return "ERROR: ${e.message}"
            }
        val command = buildCommand(gradlew, effectiveSubproject)

        logger.info {
            "[GradleCompileTool] dir='${runner.projectDir}' subproject='${effectiveSubproject ?: "<all>"}' " +
                "file='${resolvedFile ?: "<none>"}' fileFilter='${fileFilter ?: "<none>"}' " +
                "command='${command.joinToString(" ")}'"
        }

        return try {
            val result = runner.run(command, workDir = runner.workDirFor(gradlew), timeout = timeoutSeconds.seconds)
            if (result.timedOut) {
                return "ERROR: Compilation timed out after $timeoutSeconds seconds. " +
                    "Retry with a larger timeoutSeconds (max ${ToolTimeout.MAX_SECONDS}) or a narrower scope.\n\n${result.rawOutput}"
            }
            val diagnostics = diagnosticParser.parse(result.rawOutput)
            diagnosticParser.buildSummary(result.exitCode, diagnostics, result.rawOutput, fileFilter)
        } catch (e: Exception) {
            logger.error(e) { "Failed to compile" }
            "ERROR: ${e.message}"
        }
    }

    /**
     * Resolves (effectiveSubproject, fileFilter) from the raw inputs.
     *
     * Returns null if [resolvedFile] is given but no Gradle module can be found for it.
     * Returns (null, null) when both inputs are null (whole-project compile).
     */
    internal fun resolveTargets(
        resolvedSubproject: String?,
        resolvedFile: String?,
    ): Pair<String?, String?>? {
        if (resolvedSubproject != null) {
            // Subproject wins for compilation scope; file used only for filtering.
            return resolvedSubproject to resolvedFile
        }
        if (resolvedFile == null) {
            // No targeting: compile everything.
            return null to null
        }
        // Detect module from the file path.
        val fileObj =
            File(resolvedFile).let {
                if (it.isAbsolute) it else runner.projectDir.resolve(it)
            }
        val module = detectModule(fileObj) ?: return null
        return module to resolvedFile
    }

    /**
     * Walks up from [file]'s directory looking for the nearest `build.gradle.kts` or `build.gradle`.
     * Returns the Gradle module name (`:name`) or null when no build script is found before the project root.
     *
     * The project root is identified as the directory containing `settings.gradle.kts` or `settings.gradle`.
     */
    internal fun detectModule(file: File): String? {
        val projectRoot = resolveProjectRoot() ?: return null
        var dir: File? = if (file.isDirectory) file else file.parentFile
        while (dir != null && dir != projectRoot.parentFile) {
            val hasBuild =
                dir.resolve("build.gradle.kts").isFile ||
                    dir.resolve("build.gradle").isFile
            if (hasBuild && dir != projectRoot) {
                // Build the Gradle path relative to the project root.
                val rel = dir.relativeTo(projectRoot).path.replace(File.separatorChar, ':')
                return if (rel.isBlank()) null else rel
            }
            dir = dir.parentFile
        }
        return null
    }

    /**
     * Finds the project root (directory containing `settings.gradle(.kts)`) by walking up from [runner.projectDir].
     */
    internal fun resolveProjectRoot(): File? {
        var dir: File? = runner.projectDir
        while (dir != null) {
            if (dir.resolve("settings.gradle.kts").isFile || dir.resolve("settings.gradle").isFile) {
                return dir
            }
            dir = dir.parentFile
        }
        return null
    }

    internal fun buildCommand(
        gradlew: String,
        subproject: String?,
    ): List<String> {
        val task =
            if (!subproject.isNullOrBlank()) {
                val name = subproject.trimStart(':')
                ":$name:testClasses"
            } else {
                "testClasses"
            }
        return listOf(gradlew, task, "--continue", "--quiet", "--console=plain")
    }

    companion object : KLogging() {
        const val TOOL_NAME = "gradle_compile"
    }
}
