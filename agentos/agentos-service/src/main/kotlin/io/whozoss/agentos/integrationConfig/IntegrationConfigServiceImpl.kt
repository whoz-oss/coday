package io.whozoss.agentos.integrationConfig

import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Default implementation of [IntegrationConfigService].
 *
 * Delegates persistence to [IntegrationConfigRepository].
 *
 * The [upsert] method enforces the (namespaceId, name) uniqueness constraint:
 * it scans existing configs for the namespace and, if a match is found, updates
 * it in-place rather than creating a duplicate.
 */
@Service
class IntegrationConfigServiceImpl(
    private val repository: IntegrationConfigRepository,
) : IntegrationConfigService {
    override fun create(entity: IntegrationConfig): IntegrationConfig = repository.save(entity)

    override fun update(entity: IntegrationConfig): IntegrationConfig = repository.save(entity)

    override fun findByIds(ids: Collection<UUID>): List<IntegrationConfig> = repository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<IntegrationConfig> = repository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    override fun findByNamespaceAndName(
        namespaceId: UUID,
        name: String,
    ): IntegrationConfig? = repository.findByParent(namespaceId).firstOrNull { it.name == name }

    override fun upsert(config: IntegrationConfig): IntegrationConfig {
        val existing = findByNamespaceAndName(config.namespaceId, config.name)
        return if (existing == null) {
            repository.save(config)
        } else {
            repository.save(
                existing.copy(
                    integrationType = config.integrationType,
                    parameters = config.parameters,
                ),
            )
        }
    }
}
