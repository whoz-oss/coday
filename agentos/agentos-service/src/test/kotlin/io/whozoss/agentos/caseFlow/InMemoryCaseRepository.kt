package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import java.util.UUID

/** Test-only in-memory implementation of [CaseRepository]. */
class InMemoryCaseRepository : CaseRepository {

    private val delegate = InMemoryEntityRepository<Case, UUID>(
        parentIdExtractor = { it.namespaceId },
        comparator = compareBy { it.metadata.created },
    )

    // Delegate all EntityRepository methods to the shared instance so that
    // findAccessibleByUser can call findAll() on the same store.
    override fun save(entity: Case) = delegate.save(entity)
    override fun findByIds(ids: Collection<UUID>) = delegate.findByIds(ids)
    override fun findByParent(parentId: UUID) = delegate.findByParent(parentId)
    override fun delete(id: UUID) = delegate.delete(id)
    override fun deleteByParent(parentId: UUID) = delegate.deleteByParent(parentId)

    override fun findAccessibleByUserInNamespace(userId: UUID, namespaceId: UUID): List<Case> =
        findByParent(namespaceId)

    /** In tests all cases are visible to every user (no permission graph). */
    override fun findConcerningUser(userId: UUID): List<Case> = delegate.findAll()
}
