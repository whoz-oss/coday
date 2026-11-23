package io.whozoss.agentos.plugins.bash

import mu.KLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val DEFAULT_MAX_OUTPUT_CHARS = 100_000

/**
 * Executes a bash command in a given working directory with a timeout.
 *
 * The shell is `/bin/bash`, or Git Bash on Windows (see [BashShellResolver]). When no Git Bash can be found
 * on Windows, the execution answers [BashExecutionResult.Error] with an actionable message.
 *
 * The command is written to a temporary script file that the shell runs, and the file is deleted afterwards
 * (see [run] for why it is not passed with `-c`).
 *
 * Output (stdout + stderr) is capped at [maxOutputChars] to avoid overwhelming
 * the LLM context. The pattern mirrors the ripgrep execution in SearchFilesTool:
 * stdout and stderr are drained asynchronously to prevent the process from
 * blocking on a full pipe buffer before [waitFor] is called.
 *
 * [destroyForcibly] is called only on the timeout and error paths — not on
 * successful completion, where the process has already exited cleanly.
 *
 * The directory the shell ends up in (it may differ from the starting one after a `cd`) is
 * captured by an `EXIT` trap that prints `pwd` after a per-execution marker at the end of stdout.
 * The trap is installed on the same line as the command (`trap ...; <command>`), so line numbers in
 * shell diagnostics and `$LINENO` are unchanged. The marker is stripped before the output is
 * returned, so it never reaches the caller. A command that replaces the shell (`exec`) or installs
 * its own `EXIT` trap yields no working directory (null), and its stdout is left intact.
 *
 * Extra environment variables (the secret of an Auth Setting, see [BashAuthVariables]) are merged into the
 * inherited environment of the process. They are passed this way, never inside the command text, so they
 * appear neither in the logged command nor in the shell's own diagnostics; only their names are logged.
 */
object BashCommandExecutor : KLogging() {

    fun execute(
        command: String,
        workingDirectory: File,
        timeoutSeconds: Long,
        maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
        environment: Map<String, String> = emptyMap(),
    ): BashExecutionResult {
        logger.debug {
            "Running bash command in ${workingDirectory.absolutePath}: $command" +
                (if (environment.isEmpty()) "" else " (extra environment: ${environment.keys.sorted().joinToString()})")
        }
        val shell = BashShellResolver.resolve()
            ?: return BashExecutionResult.Error(BashShellResolver.windowsNotFoundMessage())
        val marker = "__AGENTOS_PWD_${UUID.randomUUID()}__:"

        val script = try {
            Files.createTempFile("agentos-bash-", ".sh").also {
                Files.write(it, wrapWithPwdTrap(command, marker, shell.pwdCommand).toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to write the script for command: $command" }
            return BashExecutionResult.Error(e.message ?: e.javaClass.simpleName)
        }
        try {
            return run(shell, script, marker, command, workingDirectory, timeoutSeconds, maxOutputChars, environment)
        } finally {
            Files.deleteIfExists(script)
        }
    }

    /**
     * The script is handed to the shell as a file, never as a `-c` argument: on Windows, `ProcessBuilder` does
     * not escape the double quotes inside an argument, so the command line is cut at the first one and the rest
     * of the script is taken for other arguments. A file is immune to that, whatever quotes the command holds.
     * The path uses forward slashes, which Git Bash and `bash` accept alike.
     */
    private fun run(
        shell: BashShell,
        script: Path,
        marker: String,
        command: String,
        workingDirectory: File,
        timeoutSeconds: Long,
        maxOutputChars: Int,
        environment: Map<String, String>,
    ): BashExecutionResult {
        val processBuilder =
            ProcessBuilder(shell.executable, script.toAbsolutePath().toString().replace('\\', '/'))
                .directory(workingDirectory)
                .redirectErrorStream(false)
        processBuilder.environment().putAll(environment)

        val process = try {
            processBuilder.start()
        } catch (e: Exception) {
            logger.error(e) { "Failed to start process for command: $command" }
            return BashExecutionResult.Error(e.message ?: e.javaClass.simpleName)
        }

        // Drain stdout and stderr asynchronously — same anti-deadlock pattern as SearchFilesTool.
        // If we read stdout synchronously after waitFor(), the process may never exit because
        // it is blocked trying to write to a full pipe buffer.
        val stdoutFuture = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        val stderrFuture = CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader().use { it.readText() }
        }

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

        return when {
            !completed -> {
                // Cancel the reader futures and forcibly terminate the process.
                // The futures may still block on stream.read() until the streams
                // are closed, which happens once destroyForcibly() kills the process.
                stdoutFuture.cancel(true)
                stderrFuture.cancel(true)
                process.destroyForcibly()
                BashExecutionResult.Timeout(timeoutSeconds)
            }
            else -> {
                try {
                    val (stdout, finalDirectory) = extractWorkingDirectory(stdoutFuture.get(), marker)
                    val stderr = stderrFuture.get()
                    BashExecutionResult.Completed(
                        exitCode = process.exitValue(),
                        stdout = stdout.take(maxOutputChars),
                        stderr = stderr.take(maxOutputChars),
                        workingDirectory = finalDirectory,
                    )
                } catch (e: Exception) {
                    logger.error(e) { "Error reading process output for command: $command" }
                    process.destroyForcibly()
                    BashExecutionResult.Error(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private fun wrapWithPwdTrap(command: String, marker: String, pwdCommand: String): String =
        "trap 'printf \"\\n%s%s\\n\" \"$marker\" \"\$($pwdCommand)\"' EXIT; $command"

    /**
     * Splits the marker line off the end of [stdout]. Returns the untouched stdout and a null
     * directory when the marker is absent.
     */
    private fun extractWorkingDirectory(stdout: String, marker: String): Pair<String, String?> {
        val index = stdout.lastIndexOf("\n$marker")
        return when {
            index < 0 -> {
                logger.debug { "No working directory marker in stdout (exec, custom EXIT trap or early exit?)" }
                stdout to null
            }
            else ->
                stdout.substring(0, index) to
                    stdout.substring(index + 1 + marker.length).removeSuffix("\n").takeIf { it.isNotBlank() }
        }
    }
}

sealed interface BashExecutionResult {
    data class Completed(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val workingDirectory: String? = null,
    ) : BashExecutionResult
    data class Timeout(val timeoutSeconds: Long) : BashExecutionResult
    data class Error(val message: String) : BashExecutionResult
}
