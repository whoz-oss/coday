package io.whozoss.agentos.prompt

import io.whozoss.agentos.entity.InMemoryEntityRepository
import java.util.UUID

/** Test-only in-memory implementation of [PromptRepository]. */
class InMemoryPromptRepository : PromptRepository {
    private val delegate =
        InMemoryEntityRepository<Prompt, String>(
            parentIdExtractor = { it.namespaceId?.toString() ?: PLATFORM_KEY },
            comparator = compareBy { it.name },
        )

    override fun save(entity: Prompt): Prompt = delegate.save(entity)

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<Prompt> = delegate.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<Prompt> = delegate.findByParent(parentId.toString())

    override fun delete(id: UUID): Boolean = delegate.delete(id)

    override fun deleteByParent(parentId: UUID): Int = delegate.deleteByParent(parentId.toString())

    override fun findPlatform(): List<Prompt> = delegate.findByParent(PLATFORM_KEY)

    companion object {
        private const val PLATFORM_KEY = "__platform__"
    }
}
