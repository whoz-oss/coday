package io.whozoss.agentos.git.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Captures exact Git object bytes while draining both pipes under one deadline. */
internal object BoundedBinaryProcessOutput {
    data class Result(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: String,
        val truncated: Boolean,
    )

    private data class Capture(val bytes: ByteArray, val truncated: Boolean)

    fun await(process: Process, timeout: Duration, maxBytes: Int): Result {
        require(maxBytes >= 0)
        val deadline = System.nanoTime() + timeout.toNanos()
        val stdout = readAsync(process.inputStream, maxBytes)
        val stderr = readAsync(process.errorStream, 4_096)
        val descendants = linkedSetOf<ProcessHandle>()

        fun remainingNanos(): Long = (deadline - System.nanoTime()).also {
            if (it <= 0) throw TimeoutException("Git object read timed out")
        }

        fun rememberDescendants() {
            descendants.removeIf { !it.isAlive }
            process.descendants().use { children -> children.forEach { descendants.add(it) } }
        }

        try {
            process.outputStream.close()
            while (true) {
                rememberDescendants()
                if (process.waitFor(minOf(remainingNanos(), TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS)) break
            }
            val out = stdout.get(remainingNanos(), TimeUnit.NANOSECONDS)
            val err = stderr.get(remainingNanos(), TimeUnit.NANOSECONDS)
            return Result(
                process.exitValue(),
                out.bytes,
                err.bytes.toString(Charsets.UTF_8),
                out.truncated || err.truncated,
            )
        } finally {
            rememberDescendants()
            descendants.toList().asReversed().forEach { if (it.isAlive) it.destroyForcibly() }
            if (process.isAlive) process.destroyForcibly()
            stdout.cancel(true)
            stderr.cancel(true)
            runCatching { process.outputStream.close() }
        }
    }

    private fun readAsync(stream: InputStream, maxBytes: Int): FutureTask<Capture> = FutureTask {
        stream.use { input ->
            val retained = ByteArrayOutputStream(minOf(maxBytes, 8_192))
            val buffer = ByteArray(8_192)
            var truncated = false
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                val keep = minOf(count, maxBytes - retained.size())
                if (keep > 0) retained.write(buffer, 0, keep)
                if (keep < count) truncated = true
            }
            Capture(retained.toByteArray(), truncated)
        }
    }.also { Thread.ofVirtual().name("agentos-git-object-output").start(it) }
}
