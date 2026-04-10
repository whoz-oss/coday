package io.whozoss.agentos.integrationConfig

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Default implementation of [IntegrationConfigService].
 *
 * Delegates persistence to [IntegrationConfigRepository].
 *
 * [create] enforces the (namespaceId, name) uniqueness constraint: a 409 is raised
 * if a config with the same name already exists in the namespace.
 * [update] replaces the entity as-is; additional business logic (schema validation,
 * credential handling, etc.) will be added here as the feature matures.
 */
@Service
class IntegrationConfigServiceImpl(
    private val repository: IntegrationConfigRepository,
) : IntegrationConfigService {
    override fun create(entity: IntegrationConfig): IntegrationConfig {
        val existing = findByNamespaceAndName(entity.namespaceId, entity.name)
        if (existing != null) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "An integration config named '${entity.name}' already exists in namespace ${entity.namespaceId}",
            )
        }
        return repository.save(entity)
    }

    override fun update(entity: IntegrationConfig): IntegrationConfig = repository.save(entity)

    override fun findByIds(ids: Collection<UUID>): List<IntegrationConfig> = repository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<IntegrationConfig> = repository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    override fun findByNamespaceAndName(
        namespaceId: UUID,
        name: String,
    ): IntegrationConfig? = repository.findByParent(namespaceId).firstOrNull { it.name == name }
}
