package io.whozoss.agentos.plugins.http

import mu.KLogging
import org.pf4j.Plugin

/**
 * PF4J plugin lifecycle for the HTTP API plugin.
 *
 * Exposes the operations of an OpenAPI 3.x described HTTP API as agent tools.
 * [start] creates the plugin-wide singletons held by [HttpApiPluginHolder]; [stop] releases them.
 */
class HttpApiPlugin : Plugin() {
    override fun start() {
        logger.info { "HTTP API Plugin started" }
        HttpApiPluginHolder.start()
    }

    override fun stop() {
        logger.info { "HTTP API Plugin stopping" }
        HttpApiPluginHolder.shutdown()
    }

    companion object : KLogging()
}
