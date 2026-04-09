package io.whozoss.agentos.llmConfig

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of [LlmConfigRepository].
 *
 * Active when `agentos.persistence.mode` is absent, `in-memory`, or any value
 * other than `neo4j` or `embedded-neo4j`.
 *
 * Because [LlmConfig.namespaceId] is nullable, we cannot use it directly as the
 * parent key for [InMemoryEntityRepository]. We store everything under a fixed
 * sentinel parent key and implement [findByNamespaceId] / [findByUserId] as scans.
 * This is acceptable — in-memory mode is temporary, Neo4j is the real implementation.
 */
@Repository
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' != 'neo4j' " +
        "and '\${agentos.persistence.mode:in-memory}' != 'embedded-neo4j'",
)
class InMemoryLlmConfigRepository : LlmConfigRepository {

    private val delegate = InMemoryEntityRepository<LlmConfig, String>(
        parentIdExtractor = { ALL_KEY },
        comparator = compareBy { it.name },
    )

    override fun save(entity: LlmConfig): LlmConfig = delegate.save(entity)

    override fun findByIds(ids: Collection<UUID>): List<LlmConfig> = delegate.findByIds(ids)

    // findByParent by convention delegates to findByNamespaceId
    override fun findByParent(parentId: UUID): List<LlmConfig> = findByNamespaceId(parentId)

    override fun delete(id: UUID): Boolean = delegate.delete(id)

    override fun deleteByParent(parentId: UUID): Int =
        findByNamespaceId(parentId).count { delegate.delete(it.metadata.id) }

    override fun findByNamespaceId(namespaceId: UUID): List<LlmConfig> =
        delegate.findAll().filter { it.namespaceId == namespaceId }

    override fun findByUserId(userId: UUID): List<LlmConfig> =
        delegate.findAll().filter { it.userId == userId }

    companion object {
        private const val ALL_KEY = "all"
    }
}
