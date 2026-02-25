package io.whozoss.agentos.plugins.datetime

import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.pf4j.Extension
import org.pf4j.Plugin
import org.pf4j.PluginWrapper

/**
 * Plugin that provides datetime-related tools.
 * This is a simple example plugin to demonstrate the tool plugin system.
 */
class DateTimePlugin(wrapper: PluginWrapper) : Plugin(wrapper) {
    override fun start() {
        logger.info { "DateTime Plugin started!" }
    }

    override fun stop() {
        logger.info { "DateTime Plugin stopped!" }
    }

    companion object : KLogging()
}

/**
 * Extension that contributes datetime tools to the registry.
 * This class is discovered by PF4J and its tools are automatically registered.
 */
@Extension
class DateTimeToolProvider : ToolPlugin {
    override fun provideTools(): List<StandardTool<*>> =
        listOf(
            GetCurrentDateTimeTool(),
        )
}
