package io.whozoss.agentos.aiModel

import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.sdk.aiProvider.AiModel
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' != 'neo4j' " +
        "and '\${agentos.persistence.mode:in-memory}' != 'embedded-neo4j'",
)
class InMemoryAiModelRepository : AiModelRepository {
    private val delegate =
        InMemoryEntityRepository<AiModel, UUID>(
            parentIdExtractor = { it.aiProviderId },
            comparator = compareBy { it.apiModelName },
        )

    override fun save(entity: AiModel): AiModel = delegate.save(entity)

    override fun findByIds(ids: Collection<UUID>): List<AiModel> = delegate.findByIds(ids)

    override fun findByParent(parentId: UUID): List<AiModel> = delegate.findByParent(parentId)

    override fun delete(id: UUID): Boolean = delegate.delete(id)

    override fun deleteByParent(parentId: UUID): Int = delegate.deleteByParent(parentId)

    // userId IS NULL filter aligns with story 6.4 AC14 — namespace-scope listing must not
    // expose user-scoped overrides (FR22, AR8).
    override fun findByNamespaceId(namespaceId: UUID): List<AiModel> =
        delegate.findAll().filter { it.namespaceId == namespaceId && it.userId == null }

    override fun findByAiProviderAndApiName(
        aiProviderId: UUID,
        apiName: String,
    ): AiModel? = findByParent(aiProviderId).firstOrNull { it.apiModelName == apiName }

    override fun findByAiProviderAndAlias(
        aiProviderId: UUID,
        alias: String,
    ): AiModel? = findByParent(aiProviderId).firstOrNull { it.alias == alias }

    override fun findByUserId(userId: UUID): List<AiModel> =
        delegate.findAll().filter { it.userId == userId }

    override fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): AiModel? =
        delegate.findAll().firstOrNull { entity ->
            entity.namespaceId == namespaceId &&
                entity.userId == userId &&
                entity.metadata.removed != true &&
                (entity.alias == name || (entity.alias == null && entity.apiModelName == name))
        }
}
