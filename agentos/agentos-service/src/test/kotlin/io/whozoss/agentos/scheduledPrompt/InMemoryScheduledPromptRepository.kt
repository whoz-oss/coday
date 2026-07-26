package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.entity.InMemoryEntityRepository
import java.util.UUID

/**
 * In-memory implementation of [ScheduledPromptRepository] for unit tests.
 *
 * Does NOT enforce DEPLOYED_TO access control — that is a Neo4j graph concern.
 * [findEffective] returns all matching layers without access-control filtering,
 * leaving the folding logic to [ScheduledPromptServiceImpl.findEffective].
 */
class InMemoryScheduledPromptRepository : ScheduledPromptRepository {
    private val delegate =
        InMemoryEntityRepository<ScheduledPrompt, String>(
            parentIdExtractor = { ALL_KEY },
            comparator = compareBy { it.name },
        )

    override fun save(entity: ScheduledPrompt): ScheduledPrompt = delegate.save(entity)

    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<ScheduledPrompt> =
        delegate.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<ScheduledPrompt> =
        delegate.findAll().filter { it.namespaceId == parentId && it.userId == null }

    override fun findPlatform(): List<ScheduledPrompt> =
        delegate.findAll().filter { it.namespaceId == null && it.userId == null }

    override fun findByTriple(namespaceId: UUID?, userId: UUID?, name: String): ScheduledPrompt? =
        delegate.findAll().firstOrNull {
            it.namespaceId == namespaceId && it.userId == userId && it.name == name
        }

    /**
     * Returns all matching layers WITHOUT access-control filtering (no DEPLOYED_TO check).
     * The folding logic is tested via [ScheduledPromptServiceImpl].
     */
    override fun findEffective(namespaceId: UUID, userId: UUID): List<ScheduledPrompt> =
        delegate.findAll().filter { sp ->
            (sp.namespaceId == null && sp.userId == null) ||
                (sp.userId == userId && sp.namespaceId == null) ||
                (sp.namespaceId == namespaceId && sp.userId == null) ||
                (sp.namespaceId == namespaceId && sp.userId == userId)
        }

    override fun findByScope(namespaceId: UUID?, userId: UUID?, agentConfigIds: List<UUID>?): List<ScheduledPrompt> =
        delegate.findAll().filter { sp ->
            sp.namespaceId == namespaceId &&
                sp.userId == userId &&
                (agentConfigIds.isNullOrEmpty() || sp.agentConfigId in agentConfigIds)
        }

    override fun delete(id: UUID): Boolean = delegate.delete(id)

    override fun deleteByParent(parentId: UUID): Int =
        findByParent(parentId).count { delegate.delete(it.metadata.id) }

    companion object {
        private const val ALL_KEY = "all"
    }
}
