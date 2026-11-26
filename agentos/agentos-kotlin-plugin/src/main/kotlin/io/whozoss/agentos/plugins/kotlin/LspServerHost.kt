package io.whozoss.agentos.plugins.kotlin

import mu.KLogging
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.isExecutable

/**
 * Where a language server listens, and the process this plugin started for it.
 *
 * @property server the port and process id the server published
 * @property launched the process started by this plugin, null when the server was already running
 */
internal data class ServerEndpoint(
    val server: PublishedServer,
    val launched: Process?,
)

/**
 * The language server of one system path: the one already running, or a new one.
 *
 * A system path has one server: the server holds a lock on it and a second one refuses to start. The server is
 * therefore looked for first, in the port file it publishes, and started only when none answers. It is started with
 * `--multi-client`, so that it outlives the connection that started it and any number of clients can use it, and with
 * `--idle-timeout`, so that it stops by itself once nobody uses it: nothing here stops it.
 *
 * What the process prints goes to a file in the system path, never to a pipe: a pipe nobody reads would block the
 * server, and one that closes with this service would break it. That file is also what an error shows the agent.
 *
 * @param serverBinary absolute path to the `intellij-server` binary, only used when a server has to be started
 * @param systemPath where the server keeps its lock, config, port file and `log/intellij-server.log`, outside the project
 * @param startTimeoutMillis how long to wait for a started server to publish its port
 * @param exitGraceMillis how long to keep looking for another server after the one we started exited, because it
 *   lost the race against a server that is still starting
 */
internal class LspServerHost(
    private val serverBinary: Path,
    private val systemPath: Path,
    private val startTimeoutMillis: Long = START_TIMEOUT_MILLIS,
    private val exitGraceMillis: Long = EXIT_GRACE_MILLIS,
) {
    private val portFile: Path = systemPath.resolve(PublishedServer.PORT_FILE_NAME)

    private val launcherLog: Path = systemPath.resolve(LAUNCHER_LOG_NAME)

    /** The server announced in the port file, when its process is alive and its port accepts connections. */
    fun findRunning(): PublishedServer? =
        readPortFile()?.takeIf { published ->
            isProcessAlive(processId = published.processId) && acceptsConnections(port = published.port)
        }

    /**
     * The running server of the system path, started when there is none.
     *
     * @throws LspInitializationException when the server cannot be started, exits, or does not publish its port in time
     * @throws IllegalArgumentException when a server has to be started and the binary is not executable
     */
    fun obtain(): ServerEndpoint {
        systemPath.createDirectories()
        return findRunning()?.let { running -> ServerEndpoint(server = running, launched = null) } ?: startServer()
    }

    private fun startServer(): ServerEndpoint {
        val process = launch()
        return try {
            awaitPublished(process = process)
        } catch (e: InterruptedException) {
            stopTree(process = process)
            Thread.currentThread().interrupt()
            throw LspInitializationException(message = "Kotlin LSP server start interrupted", cause = e)
        }
    }

    private fun launch(): Process {
        require(serverBinary.isExecutable()) { "Kotlin LSP binary not executable: $serverBinary" }
        logger.info { "Starting Kotlin LSP server: $serverBinary, system path: $systemPath" }
        val builder = ProcessBuilder(command())
        builder.environment().apply {
            clear()
            INHERITED_ENVIRONMENT.forEach { variable -> System.getenv(variable)?.let { value -> put(variable, value) } }
            putAll(TELEMETRY_DISABLED)
        }
        builder.redirectErrorStream(true)
        // appended, not truncated: a second start of the same system path would otherwise erase what the first prints
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(launcherLog.toFile()))
        // the server must not see the end of this service as the end of its input
        File(NULL_DEVICE)
            .takeIf { device ->
                device.exists()
            }?.let { device -> builder.redirectInput(ProcessBuilder.Redirect.from(device)) }
        val process =
            try {
                builder.start()
            } catch (e: IOException) {
                throw LspInitializationException(
                    message = "Kotlin LSP server could not be started ($serverBinary): ${e.message}",
                    cause = e,
                )
            }
        process.onExit().thenAccept { exited ->
            logger.info { "[intellij-server] launcher pid=${exited.pid()} exited with code ${exited.exitValue()}" }
        }
        return process
    }

    private fun command(): List<String> =
        listOf(
            serverBinary.toString(),
            "--multi-client",
            "--system-path=$systemPath",
            "--port-file=$portFile",
            "--socket=$LOOPBACK_ANY_PORT",
            "--idle-timeout=$IDLE_TIMEOUT",
        )

    private sealed interface StartOutcome {
        data class Published(
            val server: PublishedServer,
        ) : StartOutcome

        data object Exited : StartOutcome
    }

    private fun awaitPublished(process: Process): ServerEndpoint =
        when (val outcome = pollFor(timeoutMillis = startTimeoutMillis, attempt = { outcomeOf(process = process) })) {
            is StartOutcome.Published -> {
                ServerEndpoint(server = outcome.server, launched = process)
            }

            is StartOutcome.Exited -> {
                joinOrFail(process = process)
            }

            null -> {
                stopTree(process = process)
                throw LspInitializationException(
                    message =
                        "Kotlin LSP server did not publish its port within ${startTimeoutMillis / MILLIS_PER_SECOND}s: stopped. " +
                            launcherOutput(),
                )
            }
        }

    private fun outcomeOf(process: Process): StartOutcome? =
        findRunning()?.let { running -> StartOutcome.Published(server = running) }
            ?: StartOutcome.Exited.takeUnless { process.isAlive }

    /**
     * The server we started exited. Either it failed, or it lost the race against another start of the same system
     * path (the lock refused it): in that case the other server is about to publish its port, so it is joined.
     */
    private fun joinOrFail(process: Process): ServerEndpoint =
        pollFor(timeoutMillis = exitGraceMillis, attempt = { findRunning() })
            ?.also { running -> logger.info { "Another Kotlin LSP server owns $systemPath, joining it on port ${running.port}" } }
            ?.let { running -> ServerEndpoint(server = running, launched = null) }
            ?: throw LspInitializationException(
                message = "Kotlin LSP server exited with code ${process.exitValue()} before publishing its port. ${launcherOutput()}",
            )

    private fun readPortFile(): PublishedServer? =
        runCatching { Files.readString(portFile) }.getOrNull()?.let { text -> PublishedServer.parse(text = text) }

    /** What the process printed, last lines only, for the message of a failed start. */
    private fun launcherOutput(): String =
        runCatching { Files.readAllLines(launcherLog) }
            .getOrDefault(emptyList())
            .takeLast(OUTPUT_TAIL_LINES)
            .joinToString(separator = "\n")
            .takeLast(OUTPUT_TAIL_CHARS)
            .let { tail ->
                when {
                    tail.isBlank() -> "It printed nothing (log: $launcherLog)."
                    else -> "Its output (log: $launcherLog):\n$tail"
                }
            }

    /** Calls [attempt] every [POLL_MILLIS] until it returns a value or [timeoutMillis] have passed; null when none did. */
    private fun <T : Any> pollFor(
        timeoutMillis: Long,
        attempt: () -> T?,
    ): T? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var result = attempt()
        while (result == null && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
            result = attempt()
        }
        return result
    }

    companion object : KLogging() {
        /** `--idle-timeout` of a server we start: how long it lives without any client, matching the context cache. */
        const val IDLE_TIMEOUT = "1h"

        private const val LOOPBACK_ANY_PORT = "127.0.0.1:0"
        private const val LAUNCHER_LOG_NAME = "launcher.out"
        private const val NULL_DEVICE = "/dev/null"
        private const val START_TIMEOUT_MILLIS = 30_000L
        private const val EXIT_GRACE_MILLIS = 10_000L
        private const val POLL_MILLIS = 100L
        private const val CONNECT_TIMEOUT_MILLIS = 2_000
        private const val MILLIS_PER_SECOND = 1_000L
        private const val OUTPUT_TAIL_LINES = 15
        private const val OUTPUT_TAIL_CHARS = 1_500

        /** The server listens on this address only. */
        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /**
         * Variables of the service the server may need. Everything else is withheld: the service environment
         * holds its encryption key and the keys of the AI providers, and the server is a third-party binary.
         */
        private val INHERITED_ENVIRONMENT =
            listOf(
                "PATH",
                "HOME",
                "USER",
                "LANG",
                "LC_ALL",
                "TMPDIR",
                "JAVA_HOME",
                "GRADLE_USER_HOME",
                "HTTP_PROXY",
                "HTTPS_PROXY",
                "NO_PROXY",
            )

        /** The bundled JARs would try to phone home without this. */
        private val TELEMETRY_DISABLED =
            mapOf(
                "OTEL_SDK_DISABLED" to "true",
                "OTEL_TRACES_EXPORTER" to "none",
                "OTEL_METRICS_EXPORTER" to "none",
                "OTEL_LOGS_EXPORTER" to "none",
            )

        /**
         * A client socket to the server listening on [port] of the loopback interface.
         *
         * @throws IOException when nothing accepts the connection
         */
        fun openSocket(port: Int): Socket {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MILLIS)
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
            return socket
        }

        /** Whether the process [processId] exists and runs. */
        fun isProcessAlive(processId: Long): Boolean = ProcessHandle.of(processId).map { handle -> handle.isAlive }.orElse(false)

        /** Stops [process] and what it started: the binary may be a launcher script that runs the server as its child. */
        fun stopTree(process: Process) {
            runCatching { process.descendants().forEach { child -> child.destroyForcibly() } }
            runCatching { process.destroyForcibly() }
        }

        private fun acceptsConnections(port: Int): Boolean = runCatching { openSocket(port = port).close() }.isSuccess
    }
}
