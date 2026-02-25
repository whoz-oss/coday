package io.whozoss.agentos.plugin.filesystem

import mu.KLogging
import org.pf4j.Plugin
import org.pf4j.PluginWrapper

/**
 * Main plugin class for filesystem-based agents
 */
class FilesystemPlugin(
    wrapper: PluginWrapper,
) : Plugin(wrapper) {
    override fun start() {
        logger.info { "Filesystem Agents Plugin started!" }
    }

    override fun stop() {
        logger.info { "Filesystem Agents Plugin stopped!" }
    }

    companion object : KLogging()
}
