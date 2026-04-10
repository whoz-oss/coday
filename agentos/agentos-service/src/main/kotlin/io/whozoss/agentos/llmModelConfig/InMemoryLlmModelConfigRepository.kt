package io.whozoss.agentos.llmModelConfig

import io.whozoss.agentos.entity.InMemoryEntityRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' != 'neo4j' " +
        "and '\${agentos.persistence.mode:in-memory}' != 'embedded-neo4j'",
)
class InMemoryLlmModelConfigRepository : LlmModelConfigRepository {
    private val delegate = InMemoryEntityRepository<LlmModelConfig, UUID>(
        parentIdExtractor = { it.llmConfigId },
        comparator = compareBy { it.apiName },
    )

    override fun save(entity: LlmModelConfig): LlmModelConfig = delegate.save(entity)

    override fun findByIds(ids: Collection<UUID>): List<LlmModelConfig> = delegate.findByIds(ids)

    override fun findByParent(parentId: UUID): List<LlmModelConfig> = delegate.findByParent(parentId)

    override fun delete(id: UUID): Boolean = delegate.delete(id)

    override fun deleteByParent(parentId: UUID): Int = delegate.deleteByParent(parentId)

    override fun findByNamespaceId(namespaceId: UUID): List<LlmModelConfig> =
        delegate.findAll().filter { it.namespaceId == namespaceId }
}
