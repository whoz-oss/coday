package io.whozoss.agentos.aiModel

import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.sdk.aiProvider.AiModel
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
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
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A model config with alias '$alias' already exists in AiProvider ${entity.aiProviderId}",
                )
            }
        }
        val parent = aiProviderService.getById(entity.aiProviderId)
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
                    throw ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A model config with alias '$alias' already exists in AiProvider ${entity.aiProviderId}",
                    )
                }
        }
        return repository.save(entity)
    }

    override fun findByIds(ids: Collection<UUID>): List<AiModel> = repository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<AiModel> = repository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    override fun findByNamespaceId(namespaceId: UUID): List<AiModel> = repository.findByNamespaceId(namespaceId)

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
        val candidates = repository.findByNamespaceId(namespaceId)
        return candidates
            .filter { it.alias.equals(name, ignoreCase = true) }
            .maxByOrNull { it.priority }
            ?: candidates
                .filter { it.apiModelName.equals(name, ignoreCase = true) }
                .maxByOrNull { it.priority }
    }
}
