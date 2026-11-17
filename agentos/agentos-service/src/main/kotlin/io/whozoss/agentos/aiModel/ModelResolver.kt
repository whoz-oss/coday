package io.whozoss.agentos.aiModel

import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import mu.KLogging
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Resolves the [AiModel] and the [AiProvider] to call for a namespace and, when known, a user.
 *
 * The model is looked up by name in the namespace ([AiModelService.findAiModel]: alias first, then API name, the
 * namespace scope winning over the platform one), or is the namespace default when no name is given. The provider is
 * the one the model belongs to, with the overlays of the user applied when there is one
 * ([AiProviderService.resolveProvider] folds the four overlay layers by precedence); without a user, it is the
 * provider as stored.
 *
 * Lives in `aiModel`, not in `agent`, because it is shared across features: agent resolution
 * (`AgentServiceImpl`) and the generation features that call an LLM before any agent is chosen
 * (`ScheduledPromptGenerationService`).
 */
@Component
class ModelResolver(
    private val aiModelService: AiModelService,
    private val aiProviderService: AiProviderService,
) {
    /**
     * @param modelName Alias or API name of the model, or null for the namespace default.
     * @return the model and its provider, or null when the namespace has no such model.
     */
    fun resolve(
        namespaceId: UUID,
        userId: UUID?,
        modelName: String? = null,
    ): Pair<AiModel, AiProvider>? =
        findModel(namespaceId, modelName)?.let { model -> model to providerFor(model, namespaceId, userId) }

    private fun findModel(
        namespaceId: UUID,
        modelName: String?,
    ): AiModel? =
        when (modelName) {
            null -> aiModelService.findAiModel(namespaceId)
            else -> aiModelService.findAiModel(namespaceId, modelName)
        }

    /**
     * The provider of [model]: with the overlays of [userId] when there is one (all four layers fetched in a single
     * query by [AiProviderService.resolveProvider]), as stored otherwise.
     */
    private fun providerFor(
        model: AiModel,
        namespaceId: UUID,
        userId: UUID?,
    ): AiProvider {
        val baseProvider = aiProviderService.getById(model.aiProviderId)
        val provider =
            when (userId) {
                null -> baseProvider
                else -> aiProviderService.resolveProvider(namespaceId, userId, baseProvider.name)
            }
        logger.debug {
            "Resolved model '${model.alias ?: model.apiModelName}' " +
                "-> apiName='${model.apiModelName}' (priority=${model.priority}, " +
                "provider='${provider.name}', userId=$userId)"
        }
        return provider
    }

    companion object : KLogging()
}
