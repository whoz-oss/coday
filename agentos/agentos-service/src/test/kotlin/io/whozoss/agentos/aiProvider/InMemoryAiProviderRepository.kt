package io.whozoss.agentos.aiProvider

import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import java.util.UUID

/** Test-only in-memory implementation of [AiProviderRepository]. */
class InMemoryAiProviderRepository : AiProviderRepository {
    private val delegate = InMemoryEntityRepository<AiProvider, String>(
        parentIdExtractor = { ALL_KEY },
        comparator = compareBy { it.name },
    )

    override fun save(entity: AiProvider): AiProvider = delegate.save(entity)
    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<AiProvider> = delegate.findByIds(ids, withRemoved)
    override fun findByParent(parentId: UUID): List<AiProvider> = findByNamespaceId(parentId)
    override fun delete(id: UUID): Boolean = delegate.delete(id)
    override fun deleteByParent(parentId: UUID): Int =
        findByNamespaceId(parentId).count { delegate.delete(it.metadata.id) }
    override fun findByNamespaceId(namespaceId: UUID): List<AiProvider> =
        delegate.findAll().filter { it.namespaceId == namespaceId && it.userId == null }
    override fun findByUserId(userId: UUID): List<AiProvider> =
        delegate.findAll().filter { it.userId == userId }

    override fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): AiProvider? =
        delegate.findAll().firstOrNull {
            it.namespaceId == namespaceId && it.userId == userId && it.name == name
        }

    override fun findPlatformLevel(): List<AiProvider> =
        delegate.findAll().filter { it.namespaceId == null && it.userId == null }

    override fun findAllForScope(
        namespaceId: UUID,
        userId: UUID,
    ): List<AiProvider> =
        delegate.findAll().filter {
            (it.namespaceId == null || it.namespaceId == namespaceId) &&
                (it.userId == null || it.userId == userId)
        }

    companion object { private const val ALL_KEY = "all" }
}
