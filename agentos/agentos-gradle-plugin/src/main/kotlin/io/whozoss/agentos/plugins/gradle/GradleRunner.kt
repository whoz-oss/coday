package io.whozoss.agentos.plugins.gradle

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Shared Gradle process launcher used by all Gradle tools.
 *
 * Handles:
 * - `gradlew` binary resolution (walks up from [projectDir], reports subdirectory candidates)
 * - the Gradle specifics of a launch (JAVA_HOME, bounded output, `--quiet` noise filtering)
 *
 * Timeout, process tree kill (also on interruption) and bounded output drain are done by [ProcessLauncher],
 * shared with every tool of the plugin that launches a process.
 *
 * [run] is safe to call from any thread: each call owns its process.
 */
class GradleRunner(
    val projectDir: File,
    /** Max time to wait for the stdout drain to finish once the process exited or was killed. */
    private val drainGrace: Duration = ProcessSpec.DEFAULT_DRAIN_GRACE,
) {
    /**
     * Resolves the `gradlew` executable to use.
     *
     * - A non-blank [explicitPath] wins and is returned as-is.
     * - Otherwise the nearest `gradlew` is searched by walking up from [projectDir].
     *
     * @throws GradlewNotFoundException when none is found. The message tells the caller (an LLM agent)
     * how to recover: `gradlew` found in a direct subdirectory (e.g. `backend/`) are listed,
     * and the `gradlewPath` parameter is named.
     */
    fun resolveGradlew(explicitPath: String? = null): String {
        explicitPath?.takeIf { it.isNotBlank() }?.let { return it }
        var dir: File? = projectDir
        while (dir != null) {
            val candidate = File(dir, "gradlew")
            if (candidate.isFile) return candidate.absolutePath
            dir = dir.parentFile
        }
        throw GradlewNotFoundException(notFoundMessage())
    }

    /**
     * Directory the Gradle process must run in for [gradlew].
     *
     * [projectDir] when `gradlew` sits in it or in one of its ancestors (Gradle finds the build by walking up).
     * Otherwise (`gradlew` in a subdirectory such as `backend/`, or elsewhere) the `gradlew` own directory,
     * because Gradle only looks for `settings.gradle(.kts)` upwards.
     */
    fun workDirFor(gradlew: String): File {
        val gradlewDir = File(gradlew).absoluteFile.parentFile ?: return projectDir
        val project = projectDir.absoluteFile
        val gradlewDirIsProjectAncestor =
            generateSequence(project) { it.parentFile }.any { it == gradlewDir }
        return if (gradlewDirIsProjectAncestor) projectDir else gradlewDir
    }

    private fun notFoundMessage(): String {
        val base = "gradlew not found in '$projectDir' or any of its parent directories. "
        val candidates =
            projectDir
                .listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                .orEmpty()
                .map { File(it, "gradlew") }
                .filter { it.isFile }
                .map { it.absolutePath }
                .sorted()
        return if (candidates.isEmpty()) {
            base +
                "No gradlew in its direct subdirectories either. Locate it " +
                "(e.g. with a file search tool), then retry with param 'gradlewPath' " +
                "set to its absolute path."
        } else {
            base +
                "Found in subdirectories: ${candidates.joinToString(", ")}. " +
                "Retry with param 'gradlewPath' set to the right absolute path; " +
                "the build then runs from that gradlew's directory."
        }
    }

    /**
     * Launches [command] inside [workDir] (default [projectDir]), merging stderr into stdout.
     * Injects [extraEnv] on top of JAVA_HOME.
     *
     * Timeout, kill and interruption behave as in [ProcessLauncher.run]. The output is bounded to
     * [MAX_OUTPUT_CHARS]: a note at its end tells when it was cut.
     *
     * @throws InterruptedException when the calling thread is interrupted while waiting.
     * @return [RunResult] — raw stdout+stderr text, exit code, whether process finished before timeout.
     */
    fun run(
        command: List<String>,
        extraEnv: Map<String, String> = emptyMap(),
        timeout: Duration = DEFAULT_TIMEOUT_MINUTES.minutes,
        workDir: File = projectDir,
    ): RunResult {
        val result =
            ProcessLauncher.run(
                ProcessSpec(
                    command = command,
                    timeout = timeout,
                    workDir = workDir,
                    env = mapOf("JAVA_HOME" to System.getProperty("java.home")) + extraEnv,
                    maxOutputChars = MAX_OUTPUT_CHARS,
                    drainGrace = drainGrace,
                    label = "gradle",
                ),
            )
        val output = result.output.filterJvmNoise()
        return RunResult(
            rawOutput = if (result.truncated) output + TRUNCATION_NOTE else output,
            exitCode = result.exitCode,
            timedOut = result.timedOut,
        )
    }

    data class RunResult(
        val rawOutput: String,
        val exitCode: Int,
        /** true when the process was killed because it exceeded the timeout */
        val timedOut: Boolean,
    )

    companion object {
        /** Fallback of [run] for internal callers. The Gradle tools never use it: their agent must pass a timeout. */
        const val DEFAULT_TIMEOUT_MINUTES = 10L

        /**
         * Max chars of Gradle output kept: roomy for a compile failure or a test log, and far below the agent context.
         * The test results themselves come from the XML reports, not from this output.
         */
        const val MAX_OUTPUT_CHARS = 200_000

        private const val TRUNCATION_NOTE = "\n[Gradle output truncated at $MAX_OUTPUT_CHARS chars]"

        /** Single source of truth for the `gradlewPath` tool param description, shared by all Gradle tools. */
        const val GRADLEW_PATH_PARAM_DESCRIPTION =
            "Optional absolute path to the gradlew executable. " +
                "When provided, overrides the auto-resolved gradlew from the project directory, " +
                "and the build runs from that gradlew's directory when it is not in the project directory or a parent. " +
                "Use it when gradlew lives in a subdirectory (e.g. 'backend/gradlew'). " +
                "Leave null to auto-resolve."

        /** Single source of truth for how the agent must name a subproject, shared by all Gradle tools. */
        const val SUBPROJECT_NAME_HINT =
            "Subproject name = directory name of the module only (last path segment): " +
                "'integration-git' for folder 'integration/integration-git'. " +
                "Never a path or a prefix such as 'integration:integration-git'."

        /** Single source of truth for the gradlew-not-found hint appended to the Gradle tool descriptions. */
        const val GRADLEW_NOT_FOUND_HINT =
            "If gradlew is not found (e.g. it lives in a subdirectory such as 'backend/'), " +
                "the tool returns an ERROR listing candidates: retry with 'gradlewPath' set to the right absolute path."
    }
}

/**
 * Raised by [GradleRunner.resolveGradlew] when no `gradlew` is found.
 * The message is written to guide an LLM agent towards a successful retry.
 */
class GradlewNotFoundException(
    message: String,
) : RuntimeException(message)
