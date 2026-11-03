package io.whozoss.agentos.plugins.bash

import mu.KLogging
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val DEFAULT_MAX_OUTPUT_CHARS = 100_000

/**
 * Executes a bash command in a given working directory with a timeout.
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
 */
object BashCommandExecutor : KLogging() {

    fun execute(
        command: String,
        workingDirectory: File,
        timeoutSeconds: Long,
        maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
    ): BashExecutionResult {
        logger.debug { "Running bash command in ${workingDirectory.absolutePath}: $command" }
        val marker = "__AGENTOS_PWD_${UUID.randomUUID()}__:"

        val process = try {
            ProcessBuilder("/bin/bash", "-c", wrapWithPwdTrap(command, marker))
                .directory(workingDirectory)
                .redirectErrorStream(false)
                .start()
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

    private fun wrapWithPwdTrap(command: String, marker: String): String =
        "trap 'printf \"\\n%s%s\\n\" \"$marker\" \"\$(pwd)\"' EXIT; $command"

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
