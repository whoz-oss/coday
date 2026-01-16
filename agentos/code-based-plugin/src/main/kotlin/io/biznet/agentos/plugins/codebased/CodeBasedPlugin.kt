package io.whozoss.agentos.plugins.codebased

import org.pf4j.Plugin
import org.pf4j.PluginWrapper
import org.slf4j.LoggerFactory

/**
 * Main plugin class for code-based agents
 */
class CodeBasedPlugin(
    wrapper: PluginWrapper,
) : Plugin(wrapper) {
    private val logger = LoggerFactory.getLogger(CodeBasedPlugin::class.java)

    override fun start() {
        logger.info("Code-Based Agents Plugin started!")
    }

    override fun stop() {
        logger.info("Code-Based Agents Plugin stopped!")
    }
}
