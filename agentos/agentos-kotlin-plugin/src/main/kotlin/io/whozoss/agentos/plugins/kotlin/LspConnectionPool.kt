package io.whozoss.agentos.plugins.kotlin

import mu.KLogging
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The connections of this service to the language servers: one server per (binary + project) pair, found or started
 * on first use.
 *
 * A server is never started when the tools are built (that happens on every agent resolution), only when a tool
 * runs. It is shared: if another process, e.g. a second AgentOS, already runs the server of that pair, this one joins
 * it instead of starting another, which its lock would refuse (see [LspServerHost]). A connection whose server died is
 * replaced on the next call.
 *
 * Servers are not owned by the service and are never stopped by it: each stops by itself when nobody has been connected
 * for [LspServerHost.IDLE_TIMEOUT]. For that to happen a connection nobody uses must be let go, which is done for the
 * ones unused for [IDLE_RELEASE_MILLIS], whenever a call reaches the pool.
 *
 * @param clock the current time in milliseconds, replaceable to test the release of idle connections
 * @param connect how a server is found or started and connected to, replaceable so that the pool and the tools are
 *   testable without a real server
 */
internal class LspConnectionPool(
    private val resolver: KotlinLspBinaryResolver = KotlinLspBinaryResolver(),
    private val userHome: String = System.getProperty("user.home"),
    private val clock: () -> Long = System::currentTimeMillis,
    private val connect: (serverBinary: Path, projectUri: String, systemPath: Path) -> LanguageServerConnection =
        { serverBinary, projectUri, systemPath ->
            LspConnection(
                endpoint = LspServerHost(serverBinary = serverBinary, systemPath = systemPath).obtain(),
                projectUri = projectUri,
            )
        },
) {
    private val connections = mutableMapOf<String, LanguageServerConnection>()

    private val lastUsed = mutableMapOf<String, Long>()

    /**
     * The live connection to the server of the project at [root], made if there is none.
     *
     * Synchronized: a call that starts a server holds the others until its handshake ends, at most about a minute.
     *
     * @param root the project directory, as a real path
     * @param serverBinary the `serverBinary` of the integration, null to fall back on the environment and the user install
     * @throws IllegalStateException when the binary is not found or the server does not start
     */
    @Synchronized
    fun obtain(
        root: Path,
        serverBinary: String? = null,
    ): LanguageServerConnection {
        val binary = resolver.resolve(configured = serverBinary)
        val projectUri = root.toUri().toString()
        val key = "$binary:$projectUri"
        val now = clock()
        releaseIdle(now = now, keeping = key)
        val existing = connections[key]
        val connection =
            when {
                existing != null && existing.isAlive() -> {
                    existing
                }

                else -> {
                    existing?.let { dead ->
                        logger.warn { "Kotlin LSP server is gone, connecting again: $key" }
                        runCatching { dead.close() }
                        connections.remove(key)
                    }
                    connect(binary, projectUri, systemPathOf(key = key)).also { connections[key] = it }
                }
            }
        lastUsed[key] = now
        return connection
    }

    /** Hangs up from every server. The servers keep running until their idle timeout: they are shared. */
    @Synchronized
    fun closeAll() {
        connections.values.forEach { connection ->
            runCatching { connection.close() }.onFailure { logger.warn { "Error closing Kotlin LSP connection: ${it.message}" } }
        }
        connections.clear()
        lastUsed.clear()
    }

    /** Lets go of the connections unused for [IDLE_RELEASE_MILLIS], so that their server can reach its idle timeout. */
    private fun releaseIdle(
        now: Long,
        keeping: String,
    ) {
        connections.keys
            .filter { key -> key != keeping && now - (lastUsed[key] ?: now) > IDLE_RELEASE_MILLIS }
            .forEach { key ->
                logger.info { "Kotlin LSP connection unused for an hour, hanging up: $key" }
                connections.remove(key)?.let { connection -> runCatching { connection.close() } }
                lastUsed.remove(key)
            }
    }

    /**
     * Where the server of [key] (binary + project) keeps its lock, config and log: outside the project, which may be
     * a Git worktree an agent writes in, and stable, so that the server is found again. The binary is part of it: a
     * system path has one server, and two binaries would otherwise fight over it.
     */
    private fun systemPathOf(key: String): Path = Path.of(userHome, ".coday", "agentos", "kotlin-lsp", fingerprintOf(text = key))

    private fun fingerprintOf(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
            .take(FINGERPRINT_LENGTH)

    companion object : KLogging() {
        private const val FINGERPRINT_LENGTH = 16

        /** A connection unused for this long is let go: the idle timeout of the server then starts to run. */
        val IDLE_RELEASE_MILLIS: Long = TimeUnit.HOURS.toMillis(1)

        /** The pool of the running service: it hangs up when the plugin stops, or with the JVM when the plugin is not stopped. */
        val SHARED: LspConnectionPool =
            LspConnectionPool().also { pool ->
                Runtime.getRuntime().addShutdownHook(Thread { pool.closeAll() })
            }
    }
}
