package io.whozoss.agentos.plugins.mcp

import mu.KLogging
import org.pf4j.Plugin

/**
 * PF4J plugin lifecycle for the MCP plugin.
 *
 * [start] initialises the shared [McpConnectionPool] singleton.
 * [stop] shuts it down, closing all live child processes.
 */
class McpPlugin : Plugin() {
    override fun start() {
        logger.info { "MCP Plugin started" }
        McpConnectionPoolHolder.start()
    }

    override fun stop() {
        logger.info { "MCP Plugin stopping" }
        McpConnectionPoolHolder.shutdown()
    }

    companion object : KLogging()
}

/**
 * Singleton holder for the [McpConnectionPool].
 *
 * Lives in the plugin classloader. Created once when the plugin starts,
 * destroyed when the plugin stops. All [McpToolProvider] instances in the
 * same plugin JAR share the same pool.
 */
object McpConnectionPoolHolder {
    val pool = McpConnectionPool()

    fun start() = pool.start()

    fun shutdown() = pool.shutdown()
}
