package io.whozoss.agentos.aiProvider

import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of [AiProviderRepository].
 *
 * Active when `agentos.persistence.mode` is absent, `in-memory`, or any value
 * other than `neo4j` or `embedded-neo4j`.
 *
 * Because [AiProvider.namespaceId] is nullable, we cannot use it directly as the
 * parent key for [InMemoryEntityRepository]. We store everything under a fixed
 * sentinel parent key and implement [findByNamespaceId] / [findByUserId] as scans.
 * This is acceptable — in-memory mode is temporary, Neo4j is the real implementation.
 */
@Repository
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' != 'neo4j' " +
        "and '\${agentos.persistence.mode:in-memory}' != 'embedded-neo4j'",
)
class InMemoryAiProviderRepository : AiProviderRepository {
    private val delegate =
        InMemoryEntityRepository<AiProvider, String>(
            parentIdExtractor = { ALL_KEY },
            comparator = compareBy { it.name },
        )

    override fun save(entity: AiProvider): AiProvider = delegate.save(entity)

    override fun findByIds(ids: Collection<UUID>): List<AiProvider> = delegate.findByIds(ids)

    // findByParent by convention delegates to findByNamespaceId
    override fun findByParent(parentId: UUID): List<AiProvider> = findByNamespaceId(parentId)

    override fun delete(id: UUID): Boolean = delegate.delete(id)

    override fun deleteByParent(parentId: UUID): Int = findByNamespaceId(parentId).count { delegate.delete(it.metadata.id) }

    // userId IS NULL filter aligns with story 6.4 AC14 — namespace-scope listing must not
    // expose user-scoped overrides (FR22, AR8).
    override fun findByNamespaceId(namespaceId: UUID): List<AiProvider> =
        delegate.findAll().filter { it.namespaceId == namespaceId && it.userId == null }

    override fun findByUserId(userId: UUID): List<AiProvider> = delegate.findAll().filter { it.userId == userId }

    override fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): AiProvider? =
        delegate.findAll().firstOrNull {
            it.namespaceId == namespaceId && it.userId == userId && it.name == name && it.metadata.removed != true
        }

    companion object {
        private const val ALL_KEY = "all"
    }
}
