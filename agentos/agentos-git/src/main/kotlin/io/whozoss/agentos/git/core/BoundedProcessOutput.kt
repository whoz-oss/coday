package io.whozoss.agentos.git.core

import java.io.InputStream
import java.time.Duration
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Owns both output pipes and applies one deadline to the process and their readers.
 *
 * Public so the service bounds its other subprocesses (process inspection, setup) the same way.
 */
object BoundedProcessOutput {
    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val truncated: Boolean,
    )

    /** Which part of an over-long output is kept. */
    enum class Retain {
        /** Keep the first characters: the default, what every caller relied on before. */
        HEAD,

        /** Keep the last characters: for diagnostics, where the final lines hold the error. */
        TAIL,
    }

    private data class Output(
        val text: String,
        val truncated: Boolean,
    )

    fun await(
        process: Process,
        timeout: Duration,
        maxOutputChars: Int,
        retain: Retain = Retain.HEAD,
    ): Result {
        val deadline = System.nanoTime() + timeout.toNanos()
        val stdout = readAsync(process.inputStream, maxOutputChars, retain)
        val stderr = readAsync(process.errorStream, maxOutputChars, retain)
        val descendants = linkedSetOf<ProcessHandle>()

        fun remainingNanos(): Long = (deadline - System.nanoTime()).also {
            if (it <= 0) throw TimeoutException("Process execution or output reading timed out")
        }

        fun rememberDescendants() {
            descendants.removeIf { !it.isAlive }
            process.descendants().use { children -> children.forEach { descendants.add(it) } }
        }

        try {
            require(maxOutputChars >= 0) { "The process output limit must not be negative" }
            process.outputStream.close() // Neither runner supplies interactive input.
            // Retain handles while the parent lives: after it exits, a child holding a pipe open
            // may be reparented and disappear from process.descendants().
            while (true) {
                rememberDescendants()
                if (process.waitFor(minOf(remainingNanos(), TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS)) break
            }
            val capturedStdout = stdout.get(remainingNanos(), TimeUnit.NANOSECONDS)
            val capturedStderr = stderr.get(remainingNanos(), TimeUnit.NANOSECONDS)
            return Result(
                process.exitValue(),
                capturedStdout.text,
                capturedStderr.text,
                capturedStdout.truncated || capturedStderr.truncated,
            )
        } finally {
            // Interrupting a Future does not terminate a native command or close its pipe writers.
            // These commands own their descendants; none are intended to outlive this invocation.
            rememberDescendants()
            descendants.toList().asReversed().forEach { if (it.isAlive) it.destroyForcibly() }
            if (process.isAlive) process.destroyForcibly()
            stdout.cancel(true)
            stderr.cancel(true)
            runCatching { process.outputStream.close() }
        }
    }

    private fun readAsync(
        stream: InputStream,
        maxOutputChars: Int,
        retain: Retain,
    ): FutureTask<Output> = FutureTask {
        stream.bufferedReader().use { reader ->
            val output = StringBuilder()
            val buffer = CharArray(8192)
            var truncated = false
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                when (retain) {
                    Retain.HEAD -> {
                        val retained = minOf(read, maxOutputChars - output.length)
                        if (retained > 0) output.append(buffer, 0, retained)
                        if (retained < read) truncated = true
                    }

                    Retain.TAIL -> {
                        output.append(buffer, 0, read)
                        // Trim only past twice the limit: amortized O(n) instead of a shift per read.
                        if (output.length.toLong() > 2L * maxOutputChars) {
                            output.delete(0, output.length - maxOutputChars)
                            truncated = true
                        }
                    }
                }
            }
            if (retain == Retain.TAIL && output.length > maxOutputChars) {
                output.delete(0, output.length - maxOutputChars)
                truncated = true
            }
            Output(output.toString(), truncated)
        }
    }.also { Thread.ofVirtual().name("agentos-process-output").start(it) }
}
