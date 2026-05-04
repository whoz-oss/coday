package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.entity.InMemoryEntityRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of [IntegrationConfigRepository].
 *
 * Active only when `agentos.persistence.mode=in-memory`.
 *
 * Because [IntegrationConfig.namespaceId] is now nullable, we cannot use it directly as the
 * parent key for [InMemoryEntityRepository]. We store everything under a fixed
 * sentinel parent key and implement [findByNamespaceId] / [findByUserId] / [findByTriple]
 * as scans. This is acceptable — in-memory mode is only for tests/dev, Neo4j is the
 * real implementation. Pattern aligned with [io.whozoss.agentos.aiProvider.InMemoryAiProviderRepository].
 */
@Repository
@ConditionalOnProperty(name = ["agentos.persistence.mode"], havingValue = "in-memory")
class InMemoryIntegrationConfigRepository : IntegrationConfigRepository {
    private val delegate =
        InMemoryEntityRepository<IntegrationConfig, String>(
            parentIdExtractor = { ALL_KEY },
            comparator = compareBy { it.name },
        )

    override fun save(entity: IntegrationConfig): IntegrationConfig = delegate.save(entity)

    override fun findByIds(ids: Collection<UUID>): List<IntegrationConfig> = delegate.findByIds(ids)

    // findByParent by convention delegates to findByNamespaceId (Epic 4 behaviour preserved).
    override fun findByParent(parentId: UUID): List<IntegrationConfig> = findByNamespaceId(parentId)

    override fun delete(id: UUID): Boolean = delegate.delete(id)

    override fun deleteByParent(parentId: UUID): Int = findByNamespaceId(parentId).count { delegate.delete(it.metadata.id) }

    override fun findByNamespaceId(namespaceId: UUID): List<IntegrationConfig> =
        delegate.findAll().filter { it.namespaceId == namespaceId }

    override fun findByUserId(userId: UUID): List<IntegrationConfig> = delegate.findAll().filter { it.userId == userId }

    override fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): IntegrationConfig? =
        delegate.findAll().firstOrNull { it.namespaceId == namespaceId && it.userId == userId && it.name == name }

    companion object {
        private const val ALL_KEY = "all"
    }
}
