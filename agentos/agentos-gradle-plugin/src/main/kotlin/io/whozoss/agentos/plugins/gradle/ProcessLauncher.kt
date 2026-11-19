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
 * @property inheritEnvironment tells which variables of the parent environment the process inherits, by name:
 * the others are removed before [env] is added. No default: the caller must choose, a forgotten argument would
 * hand every secret of the parent to the child.
 * @property maxOutputChars max chars of the merged stdout+stderr kept: the first quarter and the last three
 * quarters of the budget. The end of a log is where a build says why it failed, the beginning where it starts: a
 * marker tells how many chars were omitted in between.
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
    val inheritEnvironment: (String) -> Boolean,
    val label: String,
    val drainGrace: Duration = DEFAULT_DRAIN_GRACE,
) {
    companion object {
        val DEFAULT_DRAIN_GRACE: Duration = 2.seconds
    }
}

/**
 * Outcome of [ProcessLauncher.run].
 *
 * @property output merged stdout+stderr, bounded to [ProcessSpec.maxOutputChars] (the beginning and the end, with a
 * marker where the middle was dropped).
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
        builder.environment().keys.retainAll { spec.inheritEnvironment(it) }
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

    /**
     * Keeps the first quarter of [max] chars read and the last three quarters, and keeps reading in between, so the
     * process never blocks on a full pipe. Memory stays bounded: the tail is trimmed back to its size each time it
     * doubles, which keeps the cost per char constant.
     */
    private class BoundedOutput(
        max: Int,
    ) {
        private val headMax = max / 4
        private val tailMax = max - headMax
        private val head = StringBuilder()
        private val tail = StringBuilder()
        private var total = 0L

        /** True when some chars were dropped. Read once the drain is over (or given up on). */
        val truncated: Boolean
            @Synchronized get() = omittedChars() > 0

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
            total += length
            val intoHead = minOf(length, headMax - head.length)
            if (intoHead > 0) head.append(chunk, 0, intoHead)
            if (intoHead < length) {
                tail.append(chunk, intoHead, length - intoHead)
                if (tail.length > 2 * tailMax) tail.delete(0, tail.length - tailMax)
            }
        }

        @Synchronized
        fun snapshot(): String {
            val keptTail = tail.substring(maxOf(0, tail.length - tailMax))
            val omitted = omittedChars()
            return if (omitted == 0L) head.toString() + keptTail else "$head\n[… $omitted chars omitted …]\n$keptTail"
        }

        private fun omittedChars(): Long = total - head.length - minOf(tail.length, tailMax)
    }

    private const val CHUNK_SIZE = 8192
}
