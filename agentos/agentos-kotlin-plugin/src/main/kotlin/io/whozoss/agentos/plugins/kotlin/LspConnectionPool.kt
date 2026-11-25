package io.whozoss.agentos.plugins.kotlin

import mu.KLogging
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The language servers started by this plugin: one process per (binary + project) pair, started on first use.
 *
 * A server is never started when the tools are built (that happens on every agent resolution), only when a tool
 * runs. A server whose process died is replaced on the next call.
 *
 * @param connect how a server is started, replaceable so that the pool and the tools are testable without a real server
 */
internal class LspConnectionPool(
    private val resolver: KotlinLspBinaryResolver = KotlinLspBinaryResolver(),
    private val userHome: String = System.getProperty("user.home"),
    private val connect: (serverBinary: Path, projectUri: String, systemPath: Path) -> LanguageServerConnection =
        { serverBinary, projectUri, systemPath ->
            LspConnection(serverBinary = serverBinary, projectUri = projectUri, systemPath = systemPath)
        },
) {
    private val connections = mutableMapOf<String, LanguageServerConnection>()

    /**
     * The live server of the project at [root], started if there is none.
     *
     * Synchronized: a call that starts a server holds the others until its handshake ends, at most 30 seconds.
     *
     * @throws IllegalStateException when the binary is not found or the server does not start
     */
    @Synchronized
    fun obtain(root: Path): LanguageServerConnection {
        val serverBinary = resolver.resolve()
        val projectUri = root.toUri().toString()
        val key = "$serverBinary:$projectUri"
        val existing = connections[key]
        return when {
            existing != null && existing.isAlive() -> {
                existing
            }

            else -> {
                existing?.let { dead ->
                    logger.warn { "Kotlin LSP server process died, restarting: $key" }
                    runCatching { dead.close() }
                    connections.remove(key)
                }
                connect(serverBinary, projectUri, systemPathOf(projectUri = projectUri)).also { connections[key] = it }
            }
        }
    }

    /** Stops every server. */
    @Synchronized
    fun closeAll() {
        connections.values.forEach { connection ->
            runCatching { connection.close() }.onFailure { logger.warn { "Error closing Kotlin LSP connection: ${it.message}" } }
        }
        connections.clear()
    }

    /**
     * Where the server keeps its index and caches: outside the project, which may be a Git worktree an agent
     * writes in, and stable, so that the index survives a restart.
     */
    private fun systemPathOf(projectUri: String): Path =
        Path.of(userHome, ".coday", "agentos", "kotlin-lsp", fingerprintOf(text = projectUri))

    private fun fingerprintOf(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
            .take(FINGERPRINT_LENGTH)

    companion object : KLogging() {
        private const val FINGERPRINT_LENGTH = 16

        /** The pool of the running service, stopped with the plugin, or with the JVM when the plugin is not stopped. */
        val SHARED: LspConnectionPool =
            LspConnectionPool().also { pool ->
                Runtime.getRuntime().addShutdownHook(Thread { pool.closeAll() })
            }
    }
}
