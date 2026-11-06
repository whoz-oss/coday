package io.whozoss.agentos.claudeCode

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import mu.KLogging
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A running `claude -p` process for one turn: AgentOS writes the user message to its input and reads the
 * `stream-json` events from its output.
 *
 * Closing it stops the process if it still runs, deletes its run directory (system prompt and MCP
 * configuration, which carries the turn's bearer token) and frees its slot in the launcher.
 */
class ClaudeCodeProcess internal constructor(
    private val process: Process,
    private val runDirectory: Path,
    private val release: () -> Unit,
) : AutoCloseable {
    private val input = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val stderr = OutputTail(process.errorStream, STDERR_TAIL_CHARS)
    private val outputTaken = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    val pid: Long get() = process.pid()

    val isAlive: Boolean get() = process.isAlive

    /** Exit code once the process has ended, null while it runs. */
    val exitCode: Int? get() = if (process.isAlive) null else process.exitValue()

    /** The last characters the CLI wrote on its error output, e.g. "No conversation found with session ID: …". */
    fun stderrTail(): String = stderr.text()

    /** Sends one user message, the way `--input-format stream-json` expects it. */
    fun sendUserMessage(text: String) {
        val line =
            MAPPER.writeValueAsString(
                mapOf("type" to "user", "message" to mapOf("role" to "user", "content" to text)),
            )
        synchronized(input) {
            input.write(line)
            input.write("\n")
            input.flush()
        }
    }

    /** Tells the CLI no other message follows: it exits once the current turn has ended. */
    fun closeInput() {
        synchronized(input) { runCatching { input.close() } }
    }

    /**
     * The events of the turn, ending when the CLI closes its output. A line longer than [MAX_LINE_CHARS]
     * is dropped and reported as [ClaudeCodeStreamEvent.Malformed]. Can be collected once.
     */
    fun events(): Flow<ClaudeCodeStreamEvent> =
        flow {
            check(outputTaken.compareAndSet(false, true)) { "The output of a Claude Code process can only be read once" }
            val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line =
                    try {
                        readBoundedLine(reader)
                    } catch (e: IOException) {
                        // The process was stopped while a line was being read.
                        logger.debug { "Claude Code output closed (pid ${process.pid()}): ${e.message}" }
                        null
                    } ?: break
                when {
                    line.oversized -> emit(ClaudeCodeStreamEvent.Malformed(line.length))
                    line.text.isNotBlank() -> emit(ClaudeCodeStreamParser.parse(line.text))
                }
            }
        }.flowOn(Dispatchers.IO)

    /** Waits until the process ends on its own, up to [timeoutMillis]; true when it has ended. */
    suspend fun awaitExit(timeoutMillis: Long): Boolean = withContext(Dispatchers.IO) { process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) }

    /**
     * Ends the process the way OpenClaw does: close its input, then SIGTERM after [STDIN_GRACE_MILLIS],
     * then SIGKILL after [TERM_GRACE_MILLIS]. The CLI's native interrupt is not used: it records a
     * "stopped by the user" message in the transcript that the next turn resumes.
     */
    suspend fun stop() = withContext(Dispatchers.IO) { terminate() }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            terminate()
        } finally {
            runCatching { runDirectory.toFile().deleteRecursively() }
                .onFailure { logger.warn { "Could not delete Claude Code run directory $runDirectory: ${it.message}" } }
            release()
        }
    }

    private fun terminate() {
        if (!process.isAlive) return
        closeInput()
        if (process.waitFor(STDIN_GRACE_MILLIS, TimeUnit.MILLISECONDS)) return
        // Remember the children before the parent goes: once it exits they are reparented and out of reach.
        val descendants = process.descendants().use { it.toList() }
        process.destroy()
        if (!process.waitFor(TERM_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
            logger.warn { "Claude Code process ${process.pid()} ignored SIGTERM, killing it" }
            process.destroyForcibly()
            process.waitFor(TERM_GRACE_MILLIS, TimeUnit.MILLISECONDS)
        }
        descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
    }

    private class Line(
        val text: String,
        val length: Int,
        val oversized: Boolean,
    )

    /** Reads up to the next line feed, keeping at most [MAX_LINE_CHARS] characters; null at end of output. */
    private fun readBoundedLine(reader: Reader): Line? {
        val text = StringBuilder()
        var length = 0
        while (true) {
            val char = reader.read()
            if (char < 0) return if (length == 0) null else Line(text.toString(), length, length > MAX_LINE_CHARS)
            if (char == '\n'.code) return Line(text.toString(), length, length > MAX_LINE_CHARS)
            length++
            if (length <= MAX_LINE_CHARS) text.append(char.toChar()) else if (text.isNotEmpty()) text.setLength(0)
        }
    }

    /** Drains a stream in the background, keeping its last [limit] characters. */
    private class OutputTail(
        stream: InputStream,
        private val limit: Int,
    ) {
        private val tail = StringBuilder()

        init {
            Thread.ofVirtual().name("claude-code-stderr").start {
                runCatching {
                    stream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(BUFFER_CHARS)
                        while (true) {
                            val read = reader.read(buffer)
                            if (read < 0) break
                            synchronized(tail) {
                                tail.append(buffer, 0, read)
                                if (tail.length > limit) tail.delete(0, tail.length - limit)
                            }
                        }
                    }
                }
            }
        }

        fun text(): String = synchronized(tail) { tail.toString() }
    }

    companion object : KLogging() {
        /** Largest output line kept, as OpenClaw does: a tool input or answer can be large, never this large. */
        const val MAX_LINE_CHARS = 8 * 1024 * 1024
        const val STDIN_GRACE_MILLIS = 500L
        const val TERM_GRACE_MILLIS = 1_000L
        private const val STDERR_TAIL_CHARS = 8 * 1024
        private const val BUFFER_CHARS = 8 * 1024
        private val MAPPER = jacksonObjectMapper()
    }
}
