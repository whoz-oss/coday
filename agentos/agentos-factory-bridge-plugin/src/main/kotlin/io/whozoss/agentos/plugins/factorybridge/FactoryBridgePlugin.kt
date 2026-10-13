package io.whozoss.agentos.plugins.factorybridge

import mu.KLogging
import org.pf4j.Plugin

class FactoryBridgePlugin : Plugin() {
    override fun start() {
        logger.info { "Factory Bridge plugin started" }
        FactoryBridgePluginHolder.start()
    }

    override fun stop() {
        FactoryBridgePluginHolder.stop()
        logger.info { "Factory Bridge plugin stopped" }
    }

    companion object : KLogging()
}
