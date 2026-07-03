package io.whozoss.agentos.prompt

import io.whozoss.agentos.entity.InMemoryEntityRepository
import java.util.UUID

/**
 * Used by [PromptServiceImplSpec] to exercise business rules (parameter name uniqueness,
 * scope isolation, soft-delete) without a Neo4j dependency.
 */
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

    override fun findByParent(parentId: UUID): List<Prompt> =
        delegate.findAll().filter { it.namespaceId == parentId && it.userId == null }

    override fun delete(id: UUID): Boolean = delegate.delete(id)

    override fun deleteByParent(parentId: UUID): Int =
        findByParent(parentId).count { delegate.delete(it.metadata.id) }

    override fun findPlatform(): List<Prompt> =
        delegate.findAll().filter { it.namespaceId == null && it.userId == null }

    override fun findByUserId(userId: UUID): List<Prompt> =
        delegate.findAll().filter { it.userId == userId }

    override fun findByTriple(namespaceId: UUID?, userId: UUID?, name: String): Prompt? =
        delegate.findAll().firstOrNull {
            it.namespaceId == namespaceId && it.userId == userId && it.name == name
        }

    companion object {
        private const val ALL_KEY = "all"
    }
}
