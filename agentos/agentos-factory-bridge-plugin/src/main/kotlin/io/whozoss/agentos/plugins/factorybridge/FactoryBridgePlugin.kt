package io.whozoss.agentos.plugins.factorybridge

import mu.KLogging
import org.pf4j.Plugin

/**
 * PF4J plugin entry point for the Factory Bridge integration.
 *
 * This plugin extracts the Factory integration logic (tools, answer interception,
 * lifecycle observation, external execution context and tool-grant policy) out of
 * AgentOS core into a standalone, optionally-loaded plugin.
 *
 * The plugin is intentionally side-effect free at startup: all state lives in the
 * [org.pf4j.Extension] implementations discovered by the host runtime, and every
 * capability is resolved lazily from the supplied [io.whozoss.agentos.sdk.tool.ToolContext].
 */
class FactoryBridgePlugin : Plugin() {
    override fun start() {
        logger.info { "Factory Bridge plugin started!" }
        FactoryBridgePluginHolder.start()
    }

    override fun stop() {
        logger.info { "Factory Bridge plugin stopped!" }
        FactoryBridgePluginHolder.shutdown()
    }

    companion object : KLogging()
}
