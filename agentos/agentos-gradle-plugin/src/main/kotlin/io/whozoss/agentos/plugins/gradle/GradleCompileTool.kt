package io.whozoss.agentos.plugins.gradle

import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import mu.KLogging
import java.io.File
import java.io.IOException
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
 *   If neither file nor subproject is given, the root build is compiled.
 *
 * The business logic ([compile]) is blocking and returns a [GradleToolResult];
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
  - Leave both null to compile the root build. On a composite build (includeBuild), pass 'subproject' to target one of its included builds.
'timeoutSeconds' is mandatory (1 to ${ToolTimeout.MAX_SECONDS}): the run is killed when it is exceeded.

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
                                "Takes priority over 'file' for compilation scope. Leave null to compile the root build.",
                        ),
                    "file" to
                        ToolSchema.string(
                            "Optional path to a source file (.kt or .java), absolute or relative to the project root. " +
                                "Used to auto-detect the owning Gradle module (if 'subproject' is absent) " +
                                "and to filter diagnostics so that the target file's errors appear first. " +
                                "Leave null when targeting a whole module or the whole project.",
                        ),
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
        return runInterruptibleTool {
            compile(
                subproject = params.subproject,
                file = params.file,
                timeoutSeconds = timeoutSeconds,
            )
        }
    }

    fun compile(
        subproject: String? = null,
        file: String? = null,
        timeoutSeconds: Int,
    ): GradleToolResult {
        ToolTimeout.validate(timeoutSeconds)?.let { return GradleToolResult.Error(it) }
        val resolvedSubproject = subproject?.takeIf { it.isNotBlank() }
        val resolvedFile = file?.takeIf { it.isNotBlank() }

        val (effectiveSubproject, fileFilter) =
            resolveTargets(resolvedSubproject, resolvedFile)
                ?: return GradleToolResult.Error(
                    "Could not locate a Gradle module for file '$resolvedFile'. " +
                        "Ensure the file path is inside the project and belongs to a Gradle subproject.",
                )

        val gradlew =
            try {
                runner.resolveGradlew()
            } catch (e: GradlewNotFoundException) {
                return GradleToolResult.Error("${e.message}")
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
                return GradleToolResult.Error(
                    "Compilation timed out after $timeoutSeconds seconds. " +
                        "Retry with a larger timeoutSeconds (max ${ToolTimeout.MAX_SECONDS}) or a narrower scope.\n\n${result.rawOutput}",
                )
            }
            val diagnostics = diagnosticParser.parse(result.rawOutput)
            val summary = diagnosticParser.buildSummary(result.exitCode, diagnostics, result.rawOutput, fileFilter)
            val hint = runner.compositeBuildHint(result.exitCode, effectiveSubproject != null, gradlew, result.rawOutput)
            GradleToolResult.ofExit(result.exitCode, if (hint == null) summary else "$summary\n\n$hint")
        } catch (e: InterruptedException) {
            // GradleRunner already killed the process tree and re-set the interrupt flag
            logger.warn { "[GradleCompileTool] interrupted" }
            GradleToolResult.Error("Compilation interrupted.")
        } catch (e: Exception) {
            logger.error(e) { "Failed to compile" }
            GradleToolResult.Error("${e.message}")
        }
    }

    /**
     * Resolves (effectiveSubproject, fileFilter) from the raw inputs.
     *
     * Returns null if [resolvedFile] is given but no Gradle module can be found for it.
     * Returns (null, null) when both inputs are null (whole-project compile).
     * A file of the root project gives (null, file): the root is compiled and the file filters the diagnostics.
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
        return when (module) {
            is OwningModule.Root -> null to resolvedFile
            is OwningModule.Module -> module.path to resolvedFile
        }
    }

    /** The Gradle project that owns a source file. */
    internal sealed interface OwningModule {
        /** The file belongs to the root project of the build: it has no module path. */
        data object Root : OwningModule

        /** The file belongs to the module [path] (`name`, or `feature:core` when nested). */
        data class Module(
            val path: String,
        ) : OwningModule
    }

    /**
     * Walks up from [file]'s directory looking for the nearest `build.gradle.kts` or `build.gradle`.
     * Returns [OwningModule.Module] with the Gradle path of the module (`name`), [OwningModule.Root] when the nearest
     * build script is the one of the build directory itself, or null when no build script is found before the build
     * root.
     *
     * The build root is the directory of the integration that contains `gradlew` and `settings.gradle(.kts)`
     * ([resolveProjectRoot]). A [file] outside it, symbolic links resolved, is refused: the walk never leaves the build.
     */
    internal fun detectModule(file: File): OwningModule? {
        val projectRoot = resolveProjectRoot() ?: return null
        if (!isInsideBuild(file)) return null
        var dir: File? = if (file.isDirectory) file else file.parentFile
        while (dir != null && dir != projectRoot.parentFile) {
            val hasBuild =
                dir.resolve("build.gradle.kts").isFile ||
                    dir.resolve("build.gradle").isFile
            if (hasBuild) {
                if (dir == projectRoot) return OwningModule.Root
                // Build the Gradle path relative to the project root.
                return OwningModule.Module(dir.relativeTo(projectRoot).path.replace(File.separatorChar, ':'))
            }
            dir = dir.parentFile
        }
        return null
    }

    /**
     * The root of the build: [GradleRunner.gradleDir] when it holds a `settings.gradle(.kts)`, else null.
     * Nothing above it is looked at: put `gradlewDirectory` on the directory that holds the settings file.
     */
    internal fun resolveProjectRoot(): File? =
        runner.gradleDir.takeIf { it.resolve("settings.gradle.kts").isFile || it.resolve("settings.gradle").isFile }

    /** True when [file], once symbolic links are resolved, is the build directory or lies under it. */
    private fun isInsideBuild(file: File): Boolean =
        try {
            file.canonicalFile.toPath().startsWith(runner.gradleDir.canonicalFile.toPath())
        } catch (e: IOException) {
            false
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
        // No --quiet: the Kotlin and javac warnings are logged at WARN level, which --quiet hides.
        return listOf(gradlew, task, "--continue", "--console=plain")
    }

    companion object : KLogging() {
        const val TOOL_NAME = "gradle_compile"
    }
}
