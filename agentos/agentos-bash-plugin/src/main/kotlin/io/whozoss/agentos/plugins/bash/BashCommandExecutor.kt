package io.whozoss.agentos.plugins.bash

import mu.KLogging
import java.io.File
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
 */
object BashCommandExecutor : KLogging() {

    fun execute(
        command: String,
        workingDirectory: File,
        timeoutSeconds: Long,
        maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
    ): BashExecutionResult {
        logger.debug { "Running bash command in ${workingDirectory.absolutePath}: $command" }

        val process = try {
            ProcessBuilder("/bin/bash", "-c", command)
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
                    val stdout = stdoutFuture.get()
                    val stderr = stderrFuture.get()
                    BashExecutionResult.Completed(
                        exitCode = process.exitValue(),
                        stdout = stdout.take(maxOutputChars),
                        stderr = stderr.take(maxOutputChars),
                    )
                } catch (e: Exception) {
                    logger.error(e) { "Error reading process output for command: $command" }
                    process.destroyForcibly()
                    BashExecutionResult.Error(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }
}

sealed interface BashExecutionResult {
    data class Completed(val exitCode: Int, val stdout: String, val stderr: String) : BashExecutionResult
    data class Timeout(val timeoutSeconds: Long) : BashExecutionResult
    data class Error(val message: String) : BashExecutionResult
}
