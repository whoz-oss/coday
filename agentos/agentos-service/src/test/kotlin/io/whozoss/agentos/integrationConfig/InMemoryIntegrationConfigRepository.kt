package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.entity.InMemoryEntityRepository
import java.util.UUID

/** Test-only in-memory implementation of [IntegrationConfigRepository]. */
class InMemoryIntegrationConfigRepository : IntegrationConfigRepository {
    private val delegate = InMemoryEntityRepository<IntegrationConfig, String>(
        parentIdExtractor = { ALL_KEY },
        comparator = compareBy { it.name },
    )

    override fun save(entity: IntegrationConfig): IntegrationConfig = delegate.save(entity)
    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<IntegrationConfig> = delegate.findByIds(ids, withRemoved)
    override fun findByParent(parentId: UUID): List<IntegrationConfig> = findByNamespaceId(parentId)
    override fun delete(id: UUID): Boolean = delegate.delete(id)
    override fun deleteByParent(parentId: UUID): Int =
        findByNamespaceId(parentId).count { delegate.delete(it.metadata.id) }
    override fun findByNamespaceId(namespaceId: UUID): List<IntegrationConfig> =
        delegate.findAll().filter { it.namespaceId == namespaceId && it.userId == null }
    override fun findByUserId(userId: UUID): List<IntegrationConfig> =
        delegate.findAll().filter { it.userId == userId }
    override fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): IntegrationConfig? =
        delegate.findAll().firstOrNull {
            it.namespaceId == namespaceId && it.userId == userId && it.name == name
        }

    companion object { private const val ALL_KEY = "all" }
}
