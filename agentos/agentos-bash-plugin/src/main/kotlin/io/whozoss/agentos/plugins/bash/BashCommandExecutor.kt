package io.whozoss.agentos.plugins.bash

import mu.KLogging
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

private const val DEFAULT_MAX_OUTPUT_CHARS = 100_000

/** Executes a non-interactive shell, retaining bounded output until that shell exits. */
object BashCommandExecutor : KLogging() {
    fun execute(
        command: String,
        workingDirectory: File,
        timeoutSeconds: Long,
        maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
        workspaceId: String? = null,
        home: File? = null,
    ): BashExecutionResult {
        require(maxOutputChars >= 0) { "The process output limit must not be negative" }
        logger.debug { "Running bash command in ${workingDirectory.absolutePath}: $command" }

        val process = try {
            ProcessBuilder("/bin/bash", "-c", command)
                .directory(workingDirectory)
                .redirectErrorStream(false)
                .also { builder -> home?.let { useWorkspaceHome(builder, it) } }
                .start()
        } catch (e: Exception) {
            logger.error(e) { "Failed to start process for command: $command" }
            return BashExecutionResult.Error(e.message ?: e.javaClass.simpleName)
        }

        if (workspaceId != null) WorkspaceBashProcesses.track(workspaceId, process)
        val stdout = AvailableOutput(process.inputStream, maxOutputChars)
        val stderr = AvailableOutput(process.errorStream, maxOutputChars)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        var completed = false
        return try {
            process.outputStream.close()
            while (true) {
                val stdoutRead = stdout.drain()
                val stderrRead = stderr.drain()
                if (!process.isAlive) {
                    // Final bytes may have arrived between the drain and the exit check. Do not
                    // wait for EOF: a background job can keep the inherited pipe open indefinitely.
                    stdout.drain()
                    stderr.drain()
                    completed = true
                    break
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return BashExecutionResult.Timeout(timeoutSeconds)
                if (!stdoutRead && !stderrRead) {
                    process.waitFor(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(10)), TimeUnit.NANOSECONDS)
                }
            }
            BashExecutionResult.Completed(process.exitValue(), stdout.text(), stderr.text())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            BashExecutionResult.Error("Bash execution was interrupted")
        } catch (e: Exception) {
            logger.error(e) { "Error reading process output for command: $command" }
            BashExecutionResult.Error(e.message ?: e.javaClass.simpleName)
        } finally {
            if (!completed) {
                if (workspaceId != null) {
                    process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                }
                process.destroyForcibly()
            }
            // No reader task remains blocked after this invocation, including when a background
            // child outlives its shell. Background services should redirect their own output.
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    /** Workspace shells share the HOME their setup used, so package stores and daemons stay per family. */
    private fun useWorkspaceHome(builder: ProcessBuilder, home: File) {
        val cache = home.resolve(".cache").also { it.mkdirs() }
        builder.environment()["HOME"] = home.absolutePath
        builder.environment()["XDG_CACHE_HOME"] = cache.absolutePath
    }

    /** Drain only bytes already buffered by the pipe; never perform a blocking read for EOF. */
    private class AvailableOutput(private val stream: InputStream, private val maxChars: Int) {
        // UTF-8 needs at most four bytes per character. Decode after collection so a multibyte
        // character split across pipe reads is preserved. Excess output is drained, not retained.
        private val maxBytes = minOf(maxChars.toLong() * 4, Int.MAX_VALUE.toLong()).toInt()
        private val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        private val buffer = ByteArray(8192)

        fun drain(): Boolean {
            var remaining = stream.available()
            val hadOutput = remaining > 0
            while (remaining > 0) {
                val count = stream.read(buffer, 0, minOf(remaining, buffer.size))
                if (count <= 0) break
                val retained = minOf(count, maxBytes - output.size())
                if (retained > 0) output.write(buffer, 0, retained)
                remaining -= count
            }
            return hadOutput
        }

        fun text(): String = output.toString(Charsets.UTF_8).take(maxChars)
    }
}

sealed interface BashExecutionResult {
    data class Completed(val exitCode: Int, val stdout: String, val stderr: String) : BashExecutionResult
    data class Timeout(val timeoutSeconds: Long) : BashExecutionResult
    data class Error(val message: String) : BashExecutionResult
}
