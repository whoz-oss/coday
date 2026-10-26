package io.whozoss.factory.verification.oracle

import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Oracle process execution, bounded output capture and classification.
 *
 * Ported from `factory/src/application/oracle/oracle-executor.ts`.
 *
 * The verdict is `exitCode == 0`, nothing else. The classification distinguishes
 * a product failure (non-zero exit code) from an empty success (cache, nothing
 * executed) and an infrastructure failure (timeout, spawn error) — but never
 * decides from the content of the output.
 *
 * Two execution mechanisms are reproduced faithfully:
 *   - [runCommand]: a command template run through a shell (`sh -c` / `cmd /c`),
 *     used for the domain commands (`pnpm nx ...`, `./gradlew ...`);
 *   - [executeOracle]: an `argv` run without a shell, used for JSON oracle
 *     definitions.
 *
 * No framework dependency: JDK `ProcessBuilder` only.
 */
object OracleExecutor {

    /** Output truncation limit for [runCommand] (matches the Node instrument). */
    const val MAX_OUTPUT_CHARS: Int = 100_000

    /** Excerpt limit for the bounded output of [executeOracle]. */
    const val BOUNDED_LIMIT: Int = 16_384

    /**
     * Hard capture ceiling per stream. Bounds memory for a runaway process; well
     * above [BOUNDED_LIMIT] and [MAX_OUTPUT_CHARS] so truncation is never masked.
     */
    private const val CAPTURE_LIMIT: Int = 1_048_576

    /** Environment variables forwarded to an oracle process (no secret propagated). */
    private val MINIMAL_ENV_KEYS = listOf(
        "PATH", "HOME", "TMPDIR", "TMP", "TEMP", "SystemRoot", "WINDIR", "PATHEXT",
    )

    private val isWindows: Boolean
        get() = System.getProperty("os.name").lowercase().contains("win")

    /**
     * Runs a shell command to completion and returns its result. The verdict is
     * `exitCode == 0`. No interpretation of stdout/stderr content.
     *
     * @param environment when `null`, the parent process environment is inherited
     *   (matching the Node instrument's `spawnSync`); otherwise it replaces it.
     */
    fun runCommand(
        command: String,
        cwd: Path? = null,
        timeoutMs: Long? = null,
        environment: Map<String, String>? = null,
    ): RunCommandResult {
        val argv = if (isWindows) listOf("cmd.exe", "/c", command) else listOf("sh", "-c", command)
        val outcome = spawn(
            argv = argv,
            cwd = cwd,
            environment = environment,
            timeoutMs = timeoutMs ?: 0L,
            minimalEnvironment = false,
        )
        return RunCommandResult(
            exitCode = if (outcome.timedOut) -1 else (outcome.exitCode ?: -1),
            stdout = truncate(outcome.stdout),
            stderr = truncate(outcome.stderr),
            durationMs = outcome.durationMs,
            timedOut = outcome.timedOut,
        )
    }

    /**
     * Runs an oracle without a shell, captures a bounded output and classifies the
     * result. On timeout the whole process tree is force-killed.
     */
    fun executeOracle(
        definition: OracleExecutionDefinition,
        repoRoot: Path,
        environment: Map<String, String> = System.getenv(),
        countTaskOutcomes: (String) -> TaskOutcomes = TaskOutcomeCounter::countTaskOutcomes,
    ): OracleExecutionResult {
        val outcome = spawn(
            argv = definition.argv,
            cwd = repoRoot,
            environment = environment,
            timeoutMs = definition.timeoutMs,
            minimalEnvironment = true,
        )

        val stdout = bounded(outcome.stdout)
        val stderr = bounded(outcome.stderr)
        val counts = countTaskOutcomes("${stdout.excerpt}\n${stderr.excerpt}")
        val observation = OracleExecutionObservation(
            exitCode = outcome.exitCode,
            signal = null,
            timedOut = outcome.timedOut,
            spawnError = outcome.spawnError,
            counts = counts,
        )
        val classification = classifyOracleExecution(definition, observation)

        return OracleExecutionResult(
            classification = classification.classification,
            outcome = classification.outcome,
            exitCode = outcome.exitCode,
            signal = null,
            timedOut = outcome.timedOut,
            durationMs = outcome.durationMs,
            spawnError = outcome.spawnError,
            counts = counts,
            stdout = stdout,
            stderr = stderr,
        )
    }

    /**
     * Classifies an oracle execution. The order of the tests matters: an
     * infrastructure failure outranks a non-zero exit code, which outranks an
     * empty success.
     */
    fun classifyOracleExecution(
        definition: OracleExecutionDefinition,
        result: OracleExecutionObservation,
    ): OracleExecutionClassification {
        if (result.spawnError != null || result.timedOut || result.signal != null) {
            return OracleExecutionClassification(OracleClassification.ORACLE_INFRASTRUCTURE, OracleOutcome.INDETERMINATE)
        }
        if (result.exitCode != 0) {
            return OracleExecutionClassification(OracleClassification.PRODUCT_REGRESSION, OracleOutcome.FAIL)
        }
        if (definition.requireWork && result.counts.executed == 0) {
            return OracleExecutionClassification(OracleClassification.EMPTY_SUCCESS, OracleOutcome.INDETERMINATE)
        }
        return OracleExecutionClassification(OracleClassification.CLEAN, OracleOutcome.PASS)
    }

    /** Builds the bounded diagnostic artifact of an oracle result. */
    fun oracleArtifact(result: OracleExecutionResult): OracleArtifact {
        val raw = buildString {
            append("{\"stdout\":")
            append(jsonString(result.stdout.excerpt))
            append(",\"stderr\":")
            append(jsonString(result.stderr.excerpt))
            append('}')
        }
        return OracleArtifact(raw = raw, hash = "sha256:${sha256Hex(raw)}")
    }

    /**
     * Resolves the real repository root, rejecting any non-absolute path. Mirrors
     * `validateOracleRoot`.
     */
    fun validateOracleRoot(repoRoot: String?): Path {
        if (repoRoot == null) throw InvalidOracleRootException()
        val path = Path.of(repoRoot)
        if (!path.isAbsolute) throw InvalidOracleRootException()
        return path.toRealPath()
    }

    /** Stable identity of a repository root (digest of its path). */
    fun oracleRootIdentity(repoRoot: Path): String = "sha256:${sha256Hex(repoRoot.toString())}"

    // ----------------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------------

    private class ProcessOutcome(
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val durationMs: Long,
        val timedOut: Boolean,
        val spawnError: String?,
    )

    private class Capture(val text: String, val truncated: Boolean)

    private fun spawn(
        argv: List<String>,
        cwd: Path?,
        environment: Map<String, String>?,
        timeoutMs: Long,
        minimalEnvironment: Boolean,
    ): ProcessOutcome {
        val startedAt = System.currentTimeMillis()
        val builder = ProcessBuilder(argv)
        if (cwd != null) builder.directory(cwd.toFile())
        // `environment == null` inherits the parent process environment
        // (runCommand's shell); a non-null environment replaces it, keeping either
        // all keys or only the minimal oracle allow-list.
        if (environment != null) {
            builder.environment().clear()
            val keys = if (minimalEnvironment) MINIMAL_ENV_KEYS else environment.keys
            for (key in keys) {
                environment[key]?.let { builder.environment()[key] = it }
            }
        }
        builder.redirectErrorStream(false)

        val process = try {
            builder.start()
        } catch (e: IOException) {
            return ProcessOutcome(
                exitCode = null,
                stdout = "",
                stderr = "",
                durationMs = System.currentTimeMillis() - startedAt,
                timedOut = false,
                spawnError = (e.message ?: "SPAWN_FAILURE"),
            )
        }

        var outCapture = Capture("", false)
        var errCapture = Capture("", false)
        val outThread = Thread { outCapture = readStream(process.inputStream, CAPTURE_LIMIT) }
        val errThread = Thread { errCapture = readStream(process.errorStream, CAPTURE_LIMIT) }
        outThread.start()
        errThread.start()

        var timedOut = false
        val effectiveTimeout = if (timeoutMs > 0) timeoutMs else Long.MAX_VALUE
        val finished = process.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS)
        if (!finished) {
            timedOut = true
            killProcessTree(process)
            process.waitFor()
        }

        outThread.join(5_000)
        errThread.join(5_000)

        return ProcessOutcome(
            exitCode = if (process.isAlive) null else process.exitValue(),
            stdout = outCapture.text,
            stderr = errCapture.text,
            durationMs = System.currentTimeMillis() - startedAt,
            timedOut = timedOut,
            spawnError = null,
        )
    }

    /**
     * Force-kills the process and every descendant. Children are captured and
     * destroyed before the parent so the tree does not get re-parented and survive.
     */
    private fun killProcessTree(process: Process) {
        runCatching {
            val descendants = process.descendants()
            try {
                descendants.forEach { handle -> runCatching { handle.destroyForcibly() } }
            } finally {
                descendants.close()
            }
        }
        runCatching { process.destroyForcibly() }
    }

    private fun readStream(stream: InputStream, limit: Int): Capture {
        val sb = StringBuilder()
        var truncated = false
        InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
            val buffer = CharArray(8192)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                if (sb.length < limit) {
                    val remaining = limit - sb.length
                    if (read <= remaining) {
                        sb.append(buffer, 0, read)
                    } else {
                        sb.append(buffer, 0, remaining)
                        truncated = true
                    }
                } else {
                    truncated = true
                }
            }
        }
        return Capture(sb.toString(), truncated)
    }

    private fun bounded(value: String): BoundedOutput =
        BoundedOutput(excerpt = value.take(BOUNDED_LIMIT), truncated = value.length > BOUNDED_LIMIT)

    private fun truncate(s: String): String =
        if (s.length <= MAX_OUTPUT_CHARS) s
        else s.substring(0, MAX_OUTPUT_CHARS) + "\n[... tronqué à $MAX_OUTPUT_CHARS caractères]"

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun jsonString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

/** Thrown when `validateOracleRoot` receives a missing or non-absolute path. */
class InvalidOracleRootException : IllegalArgumentException("INVALID_ORACLE_ROOT")
