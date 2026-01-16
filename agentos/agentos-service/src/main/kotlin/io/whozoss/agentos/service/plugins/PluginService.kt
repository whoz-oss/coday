package io.whozoss.agentos.service.plugins

import io.whozoss.agentos.sdk.agent.AgentPlugin
import org.pf4j.PluginManager
import org.pf4j.PluginState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * Service for managing the lifecycle of plugins (loading, starting, stopping, unloading).
 */
@Service
class PluginService(
    private val pluginManager: PluginManager,
) {
    private val logger = LoggerFactory.getLogger(PluginService::class.java)

    /**
     * Get all loaded plugins with their metadata
     */
    fun getLoadedPlugins(): List<PluginInfo> =
        pluginManager.plugins.map { wrapper ->
            val descriptor = wrapper.descriptor
            val agentExtensions = pluginManager.getExtensions(AgentPlugin::class.java, wrapper.pluginId)
            val agentCount = agentExtensions.sumOf { it.getAgents().size }

            PluginInfo(
                id = wrapper.pluginId,
                version = descriptor.version,
                state = wrapper.pluginState,
                description = descriptor.pluginDescription,
                provider = descriptor.provider,
                agentCount = agentCount,
                pluginPath = wrapper.pluginPath.toString(),
            )
        }

    /**
     * Load a plugin from a path
     */
    fun loadPlugin(pluginPath: Path): String {
        logger.info("Loading plugin from: $pluginPath")
        val pluginId = pluginManager.loadPlugin(pluginPath)
        pluginManager.startPlugin(pluginId)
        logger.info("Plugin loaded and started: $pluginId")
        return pluginId
    }

    /**
     * Unload a plugin
     */
    fun unloadPlugin(pluginId: String): Boolean {
        logger.info("Unloading plugin: $pluginId")
        return try {
            pluginManager.stopPlugin(pluginId)
            pluginManager.unloadPlugin(pluginId)
            logger.info("Plugin unloaded: $pluginId")
            true
        } catch (e: Exception) {
            logger.error("Failed to unload plugin '$pluginId': ${e.message}", e)
            false
        }
    }

    /**
     * Reload a plugin (unload and load again)
     */
    fun reloadPlugin(pluginId: String): Boolean {
        logger.info("Reloading plugin: $pluginId")
        val plugin = pluginManager.getPlugin(pluginId)
        if (plugin == null) {
            logger.error("Plugin not found: $pluginId")
            return false
        }

        val pluginPath = plugin.pluginPath
        return try {
            unloadPlugin(pluginId)
            loadPlugin(pluginPath)
            true
        } catch (e: Exception) {
            logger.error("Failed to reload plugin '$pluginId': ${e.message}", e)
            false
        }
    }

    /**
     * Start a plugin
     */
    fun startPlugin(pluginId: String): Boolean {
        logger.info("Starting plugin: $pluginId")
        return try {
            pluginManager.startPlugin(pluginId)
            logger.info("Plugin started: $pluginId")
            true
        } catch (e: Exception) {
            logger.error("Failed to start plugin '$pluginId': ${e.message}", e)
            false
        }
    }

    /**
     * Stop a plugin
     */
    fun stopPlugin(pluginId: String): Boolean {
        logger.info("Stopping plugin: $pluginId")
        return try {
            pluginManager.stopPlugin(pluginId)
            logger.info("Plugin stopped: $pluginId")
            true
        } catch (e: Exception) {
            logger.error("Failed to stop plugin '$pluginId': ${e.message}", e)
            false
        }
    }

    /**
     * Get plugin information by ID
     */
    fun getPluginInfo(pluginId: String): PluginInfo? {
        val wrapper = pluginManager.getPlugin(pluginId) ?: return null
        val descriptor = wrapper.descriptor
        val extensions = pluginManager.getExtensions(AgentPlugin::class.java, pluginId)
        val agentCount = extensions.sumOf { it.getAgents().size }

        return PluginInfo(
            id = wrapper.pluginId,
            version = descriptor.version,
            state = wrapper.pluginState,
            description = descriptor.pluginDescription,
            provider = descriptor.provider,
            agentCount = agentCount,
            pluginPath = wrapper.pluginPath.toString(),
        )
    }
}

/**
 * Plugin information data class
 */
data class PluginInfo(
    val id: String,
    val version: String,
    val state: PluginState,
    val description: String,
    val provider: String,
    val agentCount: Int,
    val pluginPath: String,
)
