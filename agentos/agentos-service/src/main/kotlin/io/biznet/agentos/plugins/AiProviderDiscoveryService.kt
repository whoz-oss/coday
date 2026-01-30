package io.biznet.agentos.plugins

import io.biznet.agentos.sdk.model.AiProvider
import io.biznet.agentos.sdk.plugin.AiProviderPlugin
import org.pf4j.PluginManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.jvm.java

/**
 * Service responsible for discovering and extracting AI Providers from loaded plugins.
 */
@Service
class AiProviderDiscoveryService(
    private val pluginManager: PluginManager,
) {
    private val logger = LoggerFactory.getLogger(AiProviderDiscoveryService::class.java)

    /**
     * Get all AI Providers from all loaded plugins
     */
    fun discoverAiProviders(): List<AiProvider> {
        logger.info("Searching for AiProviderPlugin extensions...")

        val extensions = pluginManager.getExtensions(AiProviderPlugin::class.java)
        logger.info("Found ${extensions.size} AiProviderPlugin extension(s) total")

        val providers = mutableListOf<AiProvider>()

        extensions.forEach { plugin ->
            try {
                logger.debug("Loading AI providers from plugin: ${plugin.getPluginId()}")
                val pluginProviders = plugin.getAiProviders()
                providers.addAll(pluginProviders)
                logger.info("Loaded ${pluginProviders.size} provider(s) from plugin '${plugin.getPluginId()}'")
            } catch (e: Exception) {
                logger.error("Failed to load AI providers from plugin '${plugin.getPluginId()}': ${e.message}", e)
            }
        }

        return providers
    }
}
