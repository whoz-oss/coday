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
        delegate.findAll().filter { it.namespaceId == namespaceId && !it.metadata.removed }

    override fun findByUserId(userId: UUID): List<IntegrationConfig> =
        delegate.findAll().filter { it.userId == userId && !it.metadata.removed }

    // Explicit `removed` filter mirrors the Neo4j repository's `(c.removed IS NULL OR c.removed = false)`
    // predicate so the two implementations stay observationally equivalent — even if a future change
    // in the in-memory delegate alters the default `findAll()` semantics.
    override fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): IntegrationConfig? =
        delegate.findAll().firstOrNull {
            it.namespaceId == namespaceId &&
                it.userId == userId &&
                it.name == name &&
                !it.metadata.removed
        }

    companion object {
        private const val ALL_KEY = "all"
    }
}
