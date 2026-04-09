package io.whozoss.agentos.llmConfig

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Default implementation of [LlmConfigService].
 *
 * Delegates persistence to [LlmConfigRepository].
 *
 * [create] enforces the (namespaceId, name) uniqueness constraint: a 409 is raised
 * if a config with the same name already exists in the namespace.
 * [update] replaces the entity as-is; the controller is responsible for resolving
 * masked [LlmConfig.apiKey] values before calling this method.
 */
@Service
class LlmConfigServiceImpl(
    private val repository: LlmConfigRepository,
) : LlmConfigService {
    override fun create(entity: LlmConfig): LlmConfig {
        val existing = findByNamespaceAndName(entity.namespaceId, entity.name)
        if (existing != null) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "An LLM config named '${entity.name}' already exists in namespace ${entity.namespaceId}",
            )
        }
        return repository.save(entity)
    }

    override fun update(entity: LlmConfig): LlmConfig = repository.save(entity)

    override fun findByIds(ids: Collection<UUID>): List<LlmConfig> = repository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<LlmConfig> = repository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    override fun findByNamespaceAndName(
        namespaceId: UUID,
        name: String,
    ): LlmConfig? = repository.findByParent(namespaceId).firstOrNull { it.name == name }
}
