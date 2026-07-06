package io.whozoss.agentos.plugins.mcp

import mu.KLogging
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val EVICTION_PERIOD_MINUTES = 1L
private const val MAX_POOL_SIZE = 200

/**
 * Plugin-internal pool of live [StdioMcpConnection] instances.
 *
 * Responsibilities:
 * - Share a single child process across all agent runs that use the same MCP server config.
 * - Evict connections that have been idle longer than [McpServerConfig.idleTimeoutMinutes].
 * - Enforce a hard cap of [MAX_POOL_SIZE] simultaneous connections.
 * - Health-check connections before returning them (restart if the process died).
 *
 * Lifecycle:
 * - [start] is called once by [McpPlugin.start].
 * - [shutdown] is called once by [McpPlugin.stop].
 *
 * Thread safety: all mutable state is guarded by [ConcurrentHashMap] and
 * [synchronized] blocks on individual [PooledConnection] entries.
 */
class McpConnectionPool {
    private val pool = ConcurrentHashMap<String, PooledConnection>()
    private lateinit var evictionExecutor: ScheduledExecutorService

    private data class PooledConnection(
        val connection: StdioMcpConnection,
        val config: McpServerConfig,
    )

    fun start() {
        evictionExecutor =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "mcp-pool-eviction").also { it.isDaemon = true }
            }
        evictionExecutor.scheduleAtFixedRate(
            ::evictIdleConnections,
            EVICTION_PERIOD_MINUTES,
            EVICTION_PERIOD_MINUTES,
            TimeUnit.MINUTES,
        )
        logger.info { "[McpPool] Started (max=$MAX_POOL_SIZE, eviction every ${EVICTION_PERIOD_MINUTES}m)" }
    }

    fun shutdown() {
        logger.info { "[McpPool] Shutting down (${pool.size} connection(s))" }
        evictionExecutor.shutdownNow()
        pool.values.forEach { it.connection.close() }
        pool.clear()
        logger.info { "[McpPool] Shutdown complete" }
    }

    /**
     * Returns a healthy [StdioMcpConnection] for [config], creating one if needed.
     *
     * Uses [ConcurrentHashMap.compute] to ensure the check-then-create sequence is atomic:
     * only one thread will ever create a connection for a given config hash, even under
     * concurrent calls.
     *
     * If an existing pooled connection for the same config hash is found but its
     * process has died, it is replaced with a fresh connection transparently.
     *
     * @throws McpConnectionException if a new connection cannot be established.
     * @throws IllegalStateException if the pool is at capacity.
     */
    fun acquire(config: McpServerConfig): StdioMcpConnection {
        val hash = config.configHash()
        var result: StdioMcpConnection? = null

        pool.compute(hash) { _, existing ->
            result =
                when {
                    existing != null && existing.connection.isAlive() -> {
                        logger.debug { "[McpPool] Reusing connection ${hash.take(8)} for '${config.label}'" }
                        existing.connection
                    }

                    existing != null -> {
                        logger.warn { "[McpPool] Connection ${hash.take(8)} is dead, reconnecting" }
                        existing.connection.close()
                        createConnection(config, hash)
                    }

                    else -> {
                        check(pool.size < MAX_POOL_SIZE) {
                            "[McpPool] Pool capacity reached ($MAX_POOL_SIZE). Cannot create new connection for '${config.label}'."
                        }
                        createConnection(config, hash)
                    }
                }
            PooledConnection(connection = result!!, config = config)
        }

        return result!!
    }

    private fun createConnection(
        config: McpServerConfig,
        hash: String,
    ): StdioMcpConnection {
        val connection = StdioMcpConnection(config, hash)
        connection.connect()
        logger.info { "[McpPool] New connection ${hash.take(8)} for '${config.label}' (pool size=${pool.size + 1})" }
        return connection
    }

    private fun evictIdleConnections() {
        val now = Instant.now()
        val toEvict =
            pool.entries.filter { (_, pooled) ->
                val idleSeconds =
                    java.time.Duration
                        .between(pooled.connection.lastUsed, now)
                        .toSeconds()
                idleSeconds > pooled.config.idleTimeoutMinutes * 60
            }
        toEvict.forEach { (hash, pooled) ->
            logger.info { "[McpPool] Evicting idle connection ${hash.take(8)} for '${pooled.config.command}'" }
            pool.remove(hash)
            pooled.connection.close()
        }
        if (toEvict.isNotEmpty()) {
            logger.info { "[McpPool] Evicted ${toEvict.size} idle connection(s), pool size=${pool.size}" }
        }
    }

    companion object : KLogging()
}
