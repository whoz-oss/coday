package io.whozoss.agentos.aiModel

import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Default implementation of [AiModelService].
 *
 * [create] resolves [namespaceId] and [userId] from the parent [AiProvider] via
 * [AiProviderService] and denormalises them onto the entity before saving, so that
 * namespace-scoped queries can be served without joining through [AiProvider].
 *
 * [create] enforces one uniqueness constraint:
 * - (aiProviderId, alias): aliases must be unambiguous within a provider config.
 *   Two configs may share the same [AiModel.apiModelName] under the same provider
 *   (e.g. same model at different temperatures) as long as their aliases differ.
 *   When [AiModel.alias] is null the check is skipped — null aliases are intentionally
 *   unconstrained so that provider-level "anonymous" configs (resolved only by apiName
 *   fallback) can coexist without conflicting with each other.
 *
 * [create] and [update] reject an [AiModel.reasoningEffort] on an Anthropic provider: Spring AI
 * 1.1.2 cannot send Anthropic's native effort and current Claude models reject a thinking
 * budget, so the setting would be silently ignored.
 */
@Service
class AiModelServiceImpl(
    private val repository: AiModelRepository,
    private val aiProviderService: AiProviderService,
) : AiModelService {
    override fun create(entity: AiModel): AiModel {
        // Uniqueness is enforced on alias only. Two configs may share the same
        // apiModelName under the same provider (e.g. same model at different
        // temperatures). When alias is null the check is intentionally skipped:
        // null-alias configs are anonymous and resolved only via apiName fallback;
        // they are allowed to accumulate and the highest-priority one wins.
        entity.alias?.let { alias ->
            findByAiProviderAndAlias(entity.aiProviderId, alias)?.let {
                throw ConflictException(
                    "A model config with alias '$alias' already exists in AiProvider ${entity.aiProviderId}",
                )
            }
        }
        val parent = aiProviderService.getById(entity.aiProviderId)
        checkReasoningEffortSupported(entity.reasoningEffort, parent.apiType)
        return repository.save(
            entity.copy(
                namespaceId = parent.namespaceId,
                userId = parent.userId,
            ),
        )
    }

    override fun update(entity: AiModel): AiModel {
        // Same uniqueness rule as create: only alias must be unique per provider.
        // Null alias skips the check for the same reason as in create.
        entity.alias?.let { alias ->
            findByAiProviderAndAlias(entity.aiProviderId, alias)
                ?.takeIf { it.id != entity.id }
                ?.let {
                    throw ConflictException(
                        "A model config with alias '$alias' already exists in AiProvider ${entity.aiProviderId}",
                    )
                }
        }
        entity.reasoningEffort?.let { effort ->
            checkReasoningEffortSupported(effort, aiProviderService.getById(entity.aiProviderId).apiType)
        }
        return repository.save(entity)
    }

    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<AiModel> = repository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<AiModel> = repository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    override fun findByNamespaceId(namespaceId: UUID): List<AiModel> = repository.findByNamespaceId(namespaceId)

    override fun findPlatformLevel(): List<AiModel> = repository.findPlatformLevel()

    override fun findAllForNamespace(namespaceId: UUID): List<AiModel> = repository.findAllForNamespace(namespaceId)

    override fun findByAiProviderAndApiName(
        aiProviderId: UUID,
        apiName: String,
    ): AiModel? = repository.findByAiProviderAndApiName(aiProviderId, apiName)

    override fun findByAiProviderAndAlias(
        aiProviderId: UUID,
        alias: String,
    ): AiModel? = repository.findByAiProviderAndAlias(aiProviderId, alias)

    override fun findAiModel(
        namespaceId: UUID,
        name: String,
    ): AiModel? {
        // Single query fetches both namespace-scoped and platform-level models.
        // At equal priority, namespace-scoped models win over platform (namespaceId=null)
        // because they are more specific. The comparator encodes this: higher scope rank
        // wins on ties, so we pick the candidate with the highest (priority, scopeRank) pair.
        val candidates = repository.findAllForNamespace(namespaceId)
        return candidates
            .filter { it.alias.equals(name, ignoreCase = true) }
            .maxWithOrNull(MODEL_COMPARATOR)
            ?: candidates
                .filter { it.apiModelName.equals(name, ignoreCase = true) }
                .maxWithOrNull(MODEL_COMPARATOR)
    }

    private fun checkReasoningEffortSupported(
        effort: ReasoningEffort?,
        apiType: AiApiType,
    ) {
        if (effort != null && apiType == AiApiType.Anthropic) {
            throw BadRequestException("reasoningEffort is not supported yet for Anthropic providers")
        }
    }

    companion object : KLogging() {
        /**
         * Comparator for model resolution: primary key is scope rank (namespace-scoped > platform),
         * secondary key is [AiModel.priority] (higher wins) to break ties within the same scope.
         *
         * Scope always wins over priority: a namespace-scoped model at priority=0 beats a
         * platform model at priority=100 because it is more specific. Priority only competes
         * among models at the same scope level.
         *
         * Scope ranks: namespace-scoped (namespaceId != null) = 1, platform = 0.
         */
        private val MODEL_COMPARATOR: Comparator<AiModel> =
            compareBy({ if (it.namespaceId != null) 1 else 0 }, { it.priority })
    }
}
