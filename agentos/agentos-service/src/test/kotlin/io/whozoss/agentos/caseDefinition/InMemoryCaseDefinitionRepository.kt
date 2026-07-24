package io.whozoss.agentos.caseDefinition

import io.whozoss.agentos.entity.InMemoryEntityRepository
import java.util.UUID

/**
 * In-memory implementation of [CaseDefinitionRepository] for unit tests.
 *
 * Calqued on [io.whozoss.agentos.prompt.InMemoryPromptRepository].
 * Does NOT enforce DEPLOYED_TO access control — that is a Neo4j graph concern.
 * [findEffective] returns all matching layers without access-control filtering,
 * leaving the folding logic to [CaseDefinitionServiceImpl.findEffective].
 */
class InMemoryCaseDefinitionRepository : CaseDefinitionRepository {
    private val delegate =
        InMemoryEntityRepository<CaseDefinition, String>(
            parentIdExtractor = { ALL_KEY },
            comparator = compareBy { it.name },
        )

    override fun save(entity: CaseDefinition): CaseDefinition = delegate.save(entity)

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<CaseDefinition> = delegate.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<CaseDefinition> =
        delegate.findAll().filter { it.namespaceId == parentId && it.userId == null }

    override fun findPlatform(): List<CaseDefinition> =
        delegate.findAll().filter { it.namespaceId == null && it.userId == null }

    override fun findByTriple(namespaceId: UUID?, userId: UUID?, name: String): CaseDefinition? =
        delegate.findAll().firstOrNull {
            it.namespaceId == namespaceId && it.userId == userId && it.name == name
        }

    /**
     * Returns all matching layers WITHOUT access-control filtering (no DEPLOYED_TO check).
     * The folding logic (sortedBy → groupBy → last) is tested via [CaseDefinitionServiceImpl].
     */
    override fun findEffective(namespaceId: UUID, userId: UUID): List<CaseDefinition> =
        delegate.findAll().filter { cd ->
            (cd.namespaceId == null && cd.userId == null) ||
                (cd.userId == userId && cd.namespaceId == null) ||
                (cd.namespaceId == namespaceId && cd.userId == null) ||
                (cd.namespaceId == namespaceId && cd.userId == userId)
        }

    override fun findByScope(
        namespaceId: UUID?,
        userId: UUID?,
        agentConfigIds: List<UUID>?,
    ): List<CaseDefinition> =
        delegate.findAll().filter { cd ->
            cd.namespaceId == namespaceId &&
                cd.userId == userId &&
                (agentConfigIds.isNullOrEmpty() || cd.agentConfigId in agentConfigIds)
        }

    override fun delete(id: UUID): Boolean = delegate.delete(id)

    override fun deleteByParent(parentId: UUID): Int =
        findByParent(parentId).count { delegate.delete(it.metadata.id) }

    companion object {
        private const val ALL_KEY = "all"
    }
}
