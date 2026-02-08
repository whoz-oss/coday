package io.whozoss.agentos.plugin.filesystem

import org.pf4j.Plugin
import org.pf4j.PluginWrapper
import org.slf4j.LoggerFactory

/**
 * Main plugin class for filesystem-based agents
 */
class FilesystemPlugin(
    wrapper: PluginWrapper,
) : Plugin(wrapper) {
    private val logger = LoggerFactory.getLogger(FilesystemPlugin::class.java)

    override fun start() {
        logger.info("Filesystem Agents Plugin started!")
    }

    override fun stop() {
        logger.info("Filesystem Agents Plugin stopped!")
    }
}
