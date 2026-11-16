package io.whozoss.agentos.agent

import io.whozoss.agentos.aiModel.AiModelService
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
 * Shared by [AgentServiceImpl], which resolves the model of an agent, and by the features that call an LLM before any
 * agent is chosen (generation of a scheduled prompt).
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
    ): Pair<AiModel, AiProvider>? {
        val baseModel =
            (if (modelName == null) aiModelService.findAiModel(namespaceId) else aiModelService.findAiModel(namespaceId, modelName))
                ?: return null
        return applyOverlaysToModel(baseModel, namespaceId, userId)
    }

    /**
     * Resolve the [AiProvider] for a pre-resolved [baseModel].
     *
     * When [userId] is non-null, delegates to [AiProviderService.resolveProvider] which
     * fetches all four overlay layers in a single query and folds them by precedence.
     * When null, falls back to a direct lookup by id — preserves Epic 4 behaviour.
     */
    private fun applyOverlaysToModel(
        baseModel: AiModel,
        namespaceId: UUID,
        userId: UUID?,
    ): Pair<AiModel, AiProvider> {
        val baseProvider = aiProviderService.getById(baseModel.aiProviderId)
        val providerConfig =
            if (userId != null) {
                aiProviderService.resolveProvider(namespaceId, userId, baseProvider.name)
            } else {
                baseProvider
            }

        logger.info {
            "Resolved model '${baseModel.alias ?: baseModel.apiModelName}' " +
                "-> apiName='${baseModel.apiModelName}' (priority=${baseModel.priority}, " +
                "provider='${providerConfig.name}', userId=$userId)"
        }
        return baseModel to providerConfig
    }

    companion object : KLogging()
}
