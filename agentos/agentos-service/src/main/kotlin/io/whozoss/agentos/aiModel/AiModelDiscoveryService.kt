package io.whozoss.agentos.aiModel

import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiModelPlugin
import mu.KLogging
import org.pf4j.PluginManager
import org.springframework.stereotype.Service

/**
 * Service responsible for discovering and extracting AI models from loaded plugins.
 */
@Service
class AiModelDiscoveryService(
    private val pluginManager: PluginManager,
) {
    /**
     * Get all AI models from all loaded plugins.
     */
    fun discoverAiModels(): List<AiModel> {
        logger.info { "Searching for AiModelPlugin extensions..." }

        val extensions = pluginManager.getExtensions(AiModelPlugin::class.java)
        logger.info { "Found ${extensions.size} AiModelPlugin extension(s)" }

        if (extensions.isEmpty()) {
            logger.warn { "No AiModelPlugin extensions found." }
        }

        val models = mutableListOf<AiModel>()

        extensions.forEach { plugin ->
            try {
                logger.debug { "Loading AI models from plugin: ${plugin.getPluginId()}" }
                val pluginModels = plugin.getAiModels()
                models.addAll(pluginModels)
                logger.info { "Loaded ${pluginModels.size} model(s) from plugin '${plugin.getPluginId()}'" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load AI models from plugin '${plugin.getPluginId()}': ${e.message}" }
            }
        }

        return models
    }

    companion object : KLogging()
}
