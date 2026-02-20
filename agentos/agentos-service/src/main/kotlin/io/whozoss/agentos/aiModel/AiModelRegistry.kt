package io.whozoss.agentos.aiModel

import io.whozoss.agentos.sdk.aiProvider.AiModel
import jakarta.annotation.PostConstruct
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
    private val aiModelDiscoveryService: AiModelDiscoveryService,
) {
    private val modelsByName = ConcurrentHashMap<String, AiModel>()

    @PostConstruct
    fun initialize() {
        logger.info { "Initializing AiModel Registry" }
        val discovered = aiModelDiscoveryService.discoverAiModels()
        discovered.forEach { register(it) }
        logger.info { "AiModel Registry initialized with ${modelsByName.size} model(s)" }
    }

    fun register(model: AiModel) {
        val key = model.name.lowercase()
        if (modelsByName.containsKey(key)) {
            logger.warn { "Overwriting existing AI model registration: ${model.name}" }
        }
        modelsByName[key] = model
        logger.info { "Registered AI model: ${model.name} (provider: ${model.providerName}, model: ${model.modelName})" }
    }

    fun findByName(name: String): AiModel? = modelsByName[name.lowercase()]

    fun getAll(): List<AiModel> = modelsByName.values.toList()

    fun getDefault(): AiModel? = modelsByName.values.firstOrNull()

    companion object : KLogging()
}
