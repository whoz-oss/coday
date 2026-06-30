package io.whozoss.agentos.prompt

import io.whozoss.agentos.entity.InMemoryEntityRepository
import java.util.UUID

/** Test-only in-memory implementation of [PromptRepository]. */
class InMemoryPromptRepository : PromptRepository {
    private val delegate =
        InMemoryEntityRepository<Prompt, String>(
            parentIdExtractor = { ALL_KEY },
            comparator = compareBy { it.name },
        )

    override fun save(entity: Prompt): Prompt = delegate.save(entity)

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<Prompt> = delegate.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<Prompt> = findByNamespaceId(parentId)

    override fun delete(id: UUID): Boolean = delegate.delete(id)

    override fun deleteByParent(parentId: UUID): Int =
        findByNamespaceId(parentId).count { delegate.delete(it.metadata.id) }

    override fun findByNamespaceId(namespaceId: UUID): List<Prompt> =
        delegate.findAll().filter { it.namespaceId == namespaceId }

    override fun findPlatform(): List<Prompt> =
        delegate.findAll().filter { it.namespaceId == null }

    companion object {
        private const val ALL_KEY = "all"
    }
}
