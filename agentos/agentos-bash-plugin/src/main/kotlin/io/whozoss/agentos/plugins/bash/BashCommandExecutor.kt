package io.whozoss.agentos.plugins.bash

import mu.KLogging
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val DEFAULT_MAX_OUTPUT_CHARS = 100_000

/**
 * Executes a bash command in a given working directory with a timeout.
 *
 * Output (stdout + stderr) is capped at [maxOutputChars] to avoid overwhelming
 * the LLM context. The pattern mirrors the ripgrep execution in SearchFilesTool:
 * stdout is drained asynchronously to prevent the process from blocking on a
 * full pipe buffer before [waitFor] is called.
 */
object BashCommandExecutor : KLogging() {

    fun execute(
        command: String,
        workingDirectory: File,
        timeoutSeconds: Long,
        maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
    ): BashExecutionResult {
        logger.debug { "Running bash command in ${workingDirectory.absolutePath}: $command" }

        var process: Process? = null
        return try {
            process = ProcessBuilder("/bin/bash", "-c", command)
                .directory(workingDirectory)
                .redirectErrorStream(false)
                .start()

            // Drain stdout and stderr asynchronously — same anti-deadlock pattern as SearchFilesTool.
            // If we read stdout synchronously after waitFor(), the process may never exit because
            // it is blocked trying to write to a full pipe buffer.
            val stdoutFuture = CompletableFuture.supplyAsync {
                process!!.inputStream.bufferedReader().use { it.readText() }
            }
            val stderrFuture = CompletableFuture.supplyAsync {
                process!!.errorStream.bufferedReader().use { it.readText() }
            }

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return BashExecutionResult.Timeout(timeoutSeconds)
            }

            val stdout = stdoutFuture.get()
            val stderr = stderrFuture.get()
            val exitCode = process.exitValue()

            BashExecutionResult.Completed(
                exitCode = exitCode,
                stdout = stdout.take(maxOutputChars),
                stderr = stderr.take(maxOutputChars),
            )
        } catch (e: Exception) {
            logger.error(e) { "Unexpected error executing bash command: $command" }
            BashExecutionResult.Error(e.message ?: e.javaClass.simpleName)
        } finally {
            process?.destroyForcibly()
        }
    }
}

sealed interface BashExecutionResult {
    data class Completed(val exitCode: Int, val stdout: String, val stderr: String) : BashExecutionResult
    data class Timeout(val timeoutSeconds: Long) : BashExecutionResult
    data class Error(val message: String) : BashExecutionResult
}
