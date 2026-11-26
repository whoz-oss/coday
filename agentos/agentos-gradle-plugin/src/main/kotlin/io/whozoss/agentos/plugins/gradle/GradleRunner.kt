package io.whozoss.agentos.plugins.gradle

import java.io.File
import java.io.IOException
import kotlin.time.Duration

/**
 * Shared Gradle process launcher used by all Gradle tools.
 *
 * Handles:
 * - `gradlew` resolution: the one that sits in [gradleDir], the `gradlewDirectory` of the integration (the project
 *   directory itself when it has none). Nothing is searched elsewhere and the agent cannot name another location.
 * - the Gradle specifics of a launch: a minimal environment (see [isInheritedVariable]), optional JAVA_HOME,
 *   bounded output, `--quiet` noise filtering
 * - the hint to give when a run that targeted no module fails on a composite build ([compositeBuildHint])
 *
 * Timeout, process tree kill (also on interruption) and bounded output drain are done by [ProcessLauncher],
 * shared with every tool of the plugin that launches a process.
 *
 * [run] is safe to call from any thread: each call owns its process.
 */
class GradleRunner(
    /** Root of the project: nothing outside it is ever run. File paths given by the agent are relative to it. */
    val projectDir: File,
    /** Max time to wait for the stdout drain to finish once the process exited or was killed. */
    private val drainGrace: Duration = ProcessSpec.DEFAULT_DRAIN_GRACE,
    /**
     * JDK Gradle runs on, passed as `JAVA_HOME`. Null leaves the `JAVA_HOME` of the service as it is (it is one of
     * the inherited variables), else the `java` found on its `PATH`.
     */
    private val javaHome: String? = null,
    /**
     * Names of the variables of the service environment to pass to Gradle on top of the default set. Everything
     * else is hidden from the build: the environment of the service holds secrets (LLM keys, tokens) that a build
     * and the tests it runs must not see.
     */
    private val inheritedEnvironment: Set<String> = emptySet(),
    /** The directory that contains `gradlew`, inside [projectDir]. Null means [projectDir] itself. */
    gradlewDirectory: File? = null,
) {
    /** The directory that contains `gradlew`, where the Gradle build starts. */
    val gradleDir: File = gradlewDirectory ?: projectDir

    /**
     * Resolves the `gradlew` executable: the file named `gradlew` in [gradleDir]. Both the directory and the file must
     * be inside [projectDir] once symbolic links are resolved, so the plugin never runs an executable that lives
     * elsewhere on the machine.
     *
     * @throws GradlewNotFoundException when there is none or it resolves outside [projectDir]. The message points at
     * the `gradlewDirectory` of the integration and lists the direct subdirectories of [projectDir] that hold one.
     */
    fun resolveGradlew(): String {
        val dir = gradleDir.absoluteFile.normalize()
        if (!isInsideProject(dir)) {
            throw GradlewNotFoundException(
                "'gradlewDirectory' ('$gradleDir') resolves outside the project directory '$projectDir': refused.",
            )
        }
        val gradlew = File(dir, GRADLEW)
        if (!gradlew.isFile) throw GradlewNotFoundException(notFoundMessage())
        if (!isInsideProject(gradlew)) {
            throw GradlewNotFoundException(
                "The gradlew of '$gradleDir' resolves outside the project directory '$projectDir': refused.",
            )
        }
        return gradlew.path
    }

    /** True when [file], once symbolic links are resolved, is [projectDir] or lies under it. */
    private fun isInsideProject(file: File): Boolean =
        try {
            file.canonicalFile.toPath().startsWith(projectDir.canonicalFile.toPath())
        } catch (e: IOException) {
            false
        }

    /** Directory the Gradle process runs in for [gradlew]: the one that contains it, where the build starts. */
    fun workDirFor(gradlew: String): File = File(gradlew).absoluteFile.parentFile ?: projectDir

    private fun notFoundMessage(): String {
        val base = "gradlew not found in '$gradleDir'. "
        val candidates =
            projectDir
                .listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                .orEmpty()
                .filter { File(it, GRADLEW).isFile }
                .map { it.absolutePath }
                .sorted()
        val where =
            if (candidates.isEmpty()) {
                "None in the direct subdirectories of '$projectDir' either. "
            } else {
                "Found in subdirectories of '$projectDir': ${candidates.joinToString(", ")}. "
            }
        return base + where +
            "Set 'gradlewDirectory' in the GRADLE integration to the directory that contains gradlew " +
            "(relative to 'projectDirectory'). This is a configuration issue, not something to retry."
    }

    /**
     * Names (directory names) of the builds that the `settings.gradle(.kts)` of the directory [from] includes with
     * `includeBuild(...)`. Commented lines are ignored. Only [from] is read, never its parents. Empty when there is
     * no settings file there or no included build.
     */
    fun includedBuilds(from: File): List<String> {
        val settings =
            SETTINGS_FILES.map { name -> File(from.absoluteFile, name) }.firstOrNull { it.isFile }
                ?: return emptyList()
        val text =
            try {
                settings.readLines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
            } catch (e: IOException) {
                return emptyList()
            }
        return INCLUDE_BUILD.findAll(text).map { File(it.groupValues[1]).name }.distinct().toList()
    }

    /**
     * Hint to append to a run that failed because Gradle could not find the task or project asked for, when the run
     * targeted no module ([targetsModule] false) while the build includes other builds: a task run without a module
     * may target the root build only, so the agent must name the module. A plain compile or test failure gets no
     * hint: the module is not the problem.
     * Null when the run succeeded, targeted a module, did not fail on a missing task or project (see [output]), or
     * the build includes nothing.
     */
    fun compositeBuildHint(
        exitCode: Int,
        targetsModule: Boolean,
        gradlew: String,
        output: String,
    ): String? {
        if (exitCode == 0 || targetsModule || !TASK_NOT_FOUND.containsMatchIn(output)) return null
        val included = includedBuilds(workDirFor(gradlew))
        if (included.isEmpty()) return null
        return "Note: this build includes other builds (${included.joinToString(", ")}). " +
            "A task run without 'subproject' may target the root build only, not the included ones: " +
            "retry with 'subproject' set to the one to target (e.g. '${included.first()}')."
    }

    /**
     * Launches [command] inside [workDir] (default [gradleDir]), merging stderr into stdout.
     *
     * The process gets the variables [isInheritedVariable] lets through, then `JAVA_HOME` when a JDK is configured,
     * then [extraEnv] on top.
     *
     * Timeout, kill and interruption behave as in [ProcessLauncher.run]. The output is bounded to
     * [MAX_OUTPUT_CHARS]: its beginning and its end are kept, a marker tells how many chars were omitted between.
     *
     * @param timeout no default: every caller states how long the build may run.
     * @throws InterruptedException when the calling thread is interrupted while waiting.
     * @return [RunResult] — raw stdout+stderr text, exit code, whether process finished before timeout.
     */
    fun run(
        command: List<String>,
        timeout: Duration,
        extraEnv: Map<String, String> = emptyMap(),
        workDir: File = gradleDir,
    ): RunResult {
        val env: Map<String, String> =
            buildMap {
                if (javaHome != null) put("JAVA_HOME", javaHome)
                putAll(extraEnv)
            }
        val result =
            ProcessLauncher.run(
                ProcessSpec(
                    command = command,
                    timeout = timeout,
                    workDir = workDir,
                    env = env,
                    maxOutputChars = MAX_OUTPUT_CHARS,
                    drainGrace = drainGrace,
                    inheritEnvironment = { name -> isInheritedVariable(name, inheritedEnvironment) },
                    label = "gradle",
                ),
            )
        return RunResult(
            rawOutput = result.output.filterJvmNoise(),
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
        /**
         * Max chars of Gradle output kept (beginning and end): roomy for a compile failure or a test log, and far
         * below the agent context. The test results themselves come from the XML reports, not from this output.
         */
        const val MAX_OUTPUT_CHARS = 200_000

        private const val GRADLEW = "gradlew"

        private val SETTINGS_FILES = listOf("settings.gradle.kts", "settings.gradle")

        /** `includeBuild("x")` (Kotlin DSL) and `includeBuild 'x'` / `includeBuild('x')` (Groovy DSL). */
        private val INCLUDE_BUILD = Regex("""includeBuild\s*\(?\s*["']([^"']+)["']""")

        /**
         * What Gradle says when the task or the project of a selector does not exist: `Task 'x' not found in root
         * project 'y'`, `Project 'x' not found in root project 'y'`, `Cannot locate tasks that match ':x:test'...`.
         */
        private val TASK_NOT_FOUND = Regex("""(?:Task|Project) '[^']*' not found in|Cannot locate tasks that match""")

        /**
         * Variables of the service environment that Gradle gets by default: what a build needs to start and to reach
         * its dependencies (path, home, JDK, locale, proxies, certificates, Gradle's own settings). Every secret of
         * the service (LLM keys, tokens, database settings) is left out. A proxy variable may carry credentials in
         * its URL: it is the one default that can.
         */
        private val INHERITED_NAMES =
            setOf(
                "PATH", "HOME", "USER", "LOGNAME", "SHELL", "TMPDIR", "TMP", "TEMP", "TZ",
                "LANG", "LANGUAGE", "TERM", "JAVA_HOME",
                "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "ALL_PROXY",
                "http_proxy", "https_proxy", "no_proxy", "all_proxy",
                "SSL_CERT_FILE", "SSL_CERT_DIR",
                "GRADLE_USER_HOME", "GRADLE_OPTS", "GRADLE_RO_DEP_CACHE",
            )

        /** Locale variables (`LC_ALL`, `LC_CTYPE`...). */
        private const val LOCALE_PREFIX = "LC_"

        /**
         * True when the variable [name] of the service environment reaches Gradle: it is in the default set, it is a
         * locale variable, or the integration lists it in [extra].
         */
        internal fun isInheritedVariable(
            name: String,
            extra: Set<String> = emptySet(),
        ): Boolean = name in INHERITED_NAMES || name.startsWith(LOCALE_PREFIX) || name in extra

        /** Single source of truth for how the agent must name a subproject, shared by all Gradle tools. */
        const val SUBPROJECT_NAME_HINT =
            "Gradle project path of the module, without the leading ':': 'cli' for a top-level module, " +
                "'feature:core' for a nested module (folder 'feature/core' by default). " +
                "In a composite build (includeBuild), an included build is addressed by its name, e.g. 'agentos-service'."
    }
}

/**
 * Raised by [GradleRunner.resolveGradlew] when no usable `gradlew` is found.
 * The message tells that it is a configuration issue: the agent cannot fix it by retrying.
 */
class GradlewNotFoundException(
    message: String,
) : RuntimeException(message)
