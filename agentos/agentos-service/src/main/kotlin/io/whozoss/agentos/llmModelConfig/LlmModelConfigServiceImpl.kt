package io.whozoss.agentos.llmModelConfig

import io.whozoss.agentos.llmConfig.LlmConfigService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Default implementation of [LlmModelConfigService].
 *
 * [create] resolves [namespaceId] and [userId] from the parent [LlmConfig] via
 * [LlmConfigService] and denormalises them onto the entity before saving, so that
 * namespace-scoped queries can be served without joining through [LlmConfig].
 *
 * [create] also enforces two uniqueness constraints:
 * - (llmConfigId, apiName): a model can only be registered once per provider config
 * - (llmConfigId, alias): aliases must be unambiguous within a provider config
 */
@Service
class LlmModelConfigServiceImpl(
    private val repository: LlmModelConfigRepository,
    private val llmConfigService: LlmConfigService,
) : LlmModelConfigService {
    override fun create(entity: LlmModelConfig): LlmModelConfig {
        findByLlmConfigAndApiName(entity.llmConfigId, entity.apiName)?.let {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "A model config for apiName '${entity.apiName}' already exists in LlmConfig ${entity.llmConfigId}",
            )
        }
        entity.alias?.let { alias ->
            findByLlmConfigAndAlias(entity.llmConfigId, alias)?.let {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A model config with alias '$alias' already exists in LlmConfig ${entity.llmConfigId}",
                )
            }
        }
        val parent = llmConfigService.getById(entity.llmConfigId)
        return repository.save(
            entity.copy(
                namespaceId = parent.namespaceId,
                userId = parent.userId,
            )
        )
    }

    override fun update(entity: LlmModelConfig): LlmModelConfig = repository.save(entity)

    override fun findByIds(ids: Collection<UUID>): List<LlmModelConfig> = repository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<LlmModelConfig> = repository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    override fun findByNamespaceId(namespaceId: UUID): List<LlmModelConfig> =
        repository.findByNamespaceId(namespaceId)

    override fun findByLlmConfigAndApiName(
        llmConfigId: UUID,
        apiName: String,
    ): LlmModelConfig? = repository.findByParent(llmConfigId).firstOrNull { it.apiName == apiName }

    override fun findByLlmConfigAndAlias(
        llmConfigId: UUID,
        alias: String,
    ): LlmModelConfig? = repository.findByParent(llmConfigId).firstOrNull { it.alias == alias }

    override fun findModelConfig(
        namespaceId: UUID,
        name: String,
    ): LlmModelConfig? {
        val candidates = repository.findByNamespaceId(namespaceId)
        return candidates
            .filter { it.alias.equals(name, ignoreCase = true) }
            .maxByOrNull { it.priority }
            ?: candidates
                .filter { it.apiName.equals(name, ignoreCase = true) }
                .maxByOrNull { it.priority }
    }
}
