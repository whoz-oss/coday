package io.whozoss.agentos.aiModel

import io.whozoss.agentos.sdk.aiProvider.AiModel
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for AI model configurations.
 *
 * Loads AiModel definitions from plugins at startup via AiModelDiscoveryService
 * and provides lookup by name. Models are identified by their name (case-insensitive)
 * and reference an AiProvider by name for actual API connectivity.
 */
@Service
class AiModelRegistry(
    aiModelDiscoveryService: AiModelDiscoveryService,
) {
    private val modelsByName = ConcurrentHashMap<String, AiModel>()

    init {
        logger.info { "Initializing AiModel Registry" }
        val discovered = aiModelDiscoveryService.discoverAiModels()
        discovered.forEach { register(it) }
        logger.info { "AiModel Registry initialized with ${modelsByName.size} model(s)" }
    }

    fun register(model: AiModel) {
        val key = (model.alias ?: model.apiModelName).lowercase()
        if (modelsByName.containsKey(key)) {
            logger.warn { "Overwriting existing AI model registration: ${model.alias ?: model.apiModelName}" }
        }
        modelsByName[key] = model
        logger.info { "Registered AI model: ${model.alias ?: model.apiModelName} (apiName: ${model.apiModelName})" }
    }

    fun findByName(name: String): AiModel? = modelsByName[name.lowercase()]

    fun getAll(): List<AiModel> = modelsByName.values.toList()

    fun getDefault(): AiModel? = modelsByName.values.sortedByDescending { it.maxTokens }.firstOrNull()

    companion object : KLogging()
}
