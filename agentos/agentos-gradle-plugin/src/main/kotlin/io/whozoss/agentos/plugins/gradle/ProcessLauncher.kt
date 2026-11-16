package io.whozoss.agentos.plugins.gradle

import mu.KLogging
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What to launch and how: see [ProcessLauncher.run].
 *
 * @property command the command line, first element being the executable.
 * @property timeout the process (and its whole tree) is killed when it runs longer.
 * @property workDir directory the process runs in.
 * @property env entries added to the inherited environment (they win over inherited ones).
 * @property maxOutputChars max chars of the merged stdout+stderr kept; the first ones are kept.
 * @property drainGrace max time to wait for the output drain once the process exited or was killed:
 * a background child may keep the pipe open long after.
 * @property label short name of what is launched, for logs and the drain thread name.
 */
internal data class ProcessSpec(
    val command: List<String>,
    val timeout: Duration,
    val workDir: File,
    val env: Map<String, String>,
    val maxOutputChars: Int,
    val drainGrace: Duration = DEFAULT_DRAIN_GRACE,
    val label: String,
) {
    companion object {
        val DEFAULT_DRAIN_GRACE: Duration = 2.seconds
    }
}

/**
 * Outcome of [ProcessLauncher.run].
 *
 * @property output merged stdout+stderr, bounded to [ProcessSpec.maxOutputChars].
 * @property exitCode the process exit code, -1 when it was killed on timeout.
 * @property timedOut true when the process was killed because it exceeded [ProcessSpec.timeout].
 * @property truncated true when the output was longer than [ProcessSpec.maxOutputChars].
 */
internal data class ProcessResult(
    val output: String,
    val exitCode: Int,
    val timedOut: Boolean,
    val truncated: Boolean,
)

/**
 * Launches an external process with a timeout, a bounded output and a whole-tree kill.
 *
 * - stderr is merged into stdout, drained concurrently on its own thread so a full pipe never blocks the process;
 * - on timeout the WHOLE process tree is killed (a Gradle wrapper spawns daemons and children), the partial
 *   output is kept, the exit code is -1;
 * - when the calling thread is interrupted while waiting, the tree is killed, the interrupt flag is set again
 *   and the [InterruptedException] is rethrown;
 * - once the process exited or was killed, the drain is awaited for at most [ProcessSpec.drainGrace]: a
 *   background child may hold the pipe open, what was read so far is returned.
 *
 * Safe to call from any thread: each call owns its process.
 */
internal object ProcessLauncher : KLogging() {
    /**
     * @throws InterruptedException when the calling thread is interrupted while waiting (the process tree is
     * killed first and the interrupt flag is kept).
     * @throws IOException when the process cannot be started.
     */
    fun run(spec: ProcessSpec): ProcessResult {
        val builder =
            ProcessBuilder(spec.command)
                .directory(spec.workDir)
                .redirectErrorStream(true)
        builder.environment().putAll(spec.env)
        val process = builder.start()

        val output = BoundedOutput(spec.maxOutputChars)
        val drain =
            Thread({ output.drain(process.inputStream) }, "${spec.label}-output-drain").apply {
                isDaemon = true
                start()
            }

        var timedOut = false
        try {
            timedOut = !process.waitFor(spec.timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            if (timedOut) {
                logger.warn { "[${spec.label}] timed out after ${spec.timeout}, killing the process tree" }
                killTree(process)
            }
            // join(0) would wait forever
            drain.join(spec.drainGrace.inWholeMilliseconds.coerceAtLeast(1))
        } catch (e: InterruptedException) {
            killTree(process)
            Thread.currentThread().interrupt()
            throw e
        }

        return ProcessResult(
            output = output.snapshot(),
            exitCode = if (timedOut) -1 else process.exitValue(),
            timedOut = timedOut,
            truncated = output.truncated,
        )
    }

    private fun killTree(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    /** Keeps the first [max] chars read and keeps reading after that, so the process never blocks on a full pipe. */
    private class BoundedOutput(
        private val max: Int,
    ) {
        private val buffer = StringBuilder()

        @Volatile
        var truncated = false
            private set

        fun drain(stream: InputStream) {
            try {
                InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                    val chunk = CharArray(CHUNK_SIZE)
                    while (true) {
                        val read = reader.read(chunk)
                        if (read < 0) break
                        append(chunk, read)
                    }
                }
            } catch (e: IOException) {
                // the stream is closed when the process is killed: what was read is kept
            }
        }

        @Synchronized
        private fun append(
            chunk: CharArray,
            length: Int,
        ) {
            val room = max - buffer.length
            if (room > 0) buffer.append(chunk, 0, minOf(length, room))
            if (length > room) truncated = true
        }

        @Synchronized
        fun snapshot(): String = buffer.toString()
    }

    private const val CHUNK_SIZE = 8192
}
