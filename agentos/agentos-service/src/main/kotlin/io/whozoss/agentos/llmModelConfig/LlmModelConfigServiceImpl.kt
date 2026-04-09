package io.whozoss.agentos.llmModelConfig

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Default implementation of [LlmModelConfigService].
 *
 * Delegates persistence to [LlmModelConfigRepository].
 *
 * [create] enforces two uniqueness constraints:
 * - (llmConfigId, apiName): a model can only be registered once per provider config
 * - (llmConfigId, alias): aliases must be unambiguous within a provider config
 *
 * [update] replaces the entity as-is; the caller is responsible for not violating
 * the uniqueness constraints (a future iteration may add explicit update validation).
 */
@Service
class LlmModelConfigServiceImpl(
    private val repository: LlmModelConfigRepository,
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
        return repository.save(entity)
    }

    override fun update(entity: LlmModelConfig): LlmModelConfig = repository.save(entity)

    override fun findByIds(ids: Collection<UUID>): List<LlmModelConfig> = repository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<LlmModelConfig> = repository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    override fun findByLlmConfigAndApiName(
        llmConfigId: UUID,
        apiName: String,
    ): LlmModelConfig? = repository.findByParent(llmConfigId).firstOrNull { it.apiName == apiName }

    override fun findByLlmConfigAndAlias(
        llmConfigId: UUID,
        alias: String,
    ): LlmModelConfig? = repository.findByParent(llmConfigId).firstOrNull { it.alias == alias }
}
