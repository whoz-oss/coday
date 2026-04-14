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
 * [create] also enforces two uniqueness constraints:
 * - (aiProviderId, apiName): a model can only be registered once per provider config
 * - (aiProviderId, alias): aliases must be unambiguous within a provider config
 */
@Service
class AiModelServiceImpl(
    private val repository: AiModelRepository,
    private val aiProviderService: AiProviderService,
) : AiModelService {
    override fun create(entity: AiModel): AiModel {
        findByAiProviderAndApiName(entity.aiProviderId, entity.apiName)?.let {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "A model config for apiName '${entity.apiName}' already exists in AiProvider ${entity.aiProviderId}",
            )
        }
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
        findByAiProviderAndApiName(entity.aiProviderId, entity.apiName)
            ?.takeIf { it.id != entity.id }
            ?.let {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A model config for apiName '${entity.apiName}' already exists in AiProvider ${entity.aiProviderId}",
                )
            }
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
                .filter { it.apiName.equals(name, ignoreCase = true) }
                .maxByOrNull { it.priority }
    }
}
