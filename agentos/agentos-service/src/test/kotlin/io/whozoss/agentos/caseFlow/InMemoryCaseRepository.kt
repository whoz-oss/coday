package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.InMemoryEntityRepository
import java.util.*

/** Test-only in-memory implementation of [CaseRepository]. */
class InMemoryCaseRepository : CaseRepository {
    private val delegate =
        InMemoryEntityRepository<Case, UUID>(
            parentIdExtractor = { it.namespaceId },
            comparator = compareBy { it.metadata.created },
        )

    // Delegate all EntityRepository methods to the shared instance so that
    // findAccessibleByUser can call findAll() on the same store.
    override fun save(entity: Case) = delegate.save(entity)

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ) = delegate.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID) = delegate.findByParent(parentId)

    override fun delete(id: UUID) = delegate.delete(id)

    override fun deleteByParent(parentId: UUID) = delegate.deleteByParent(parentId)

    override fun findAccessibleByUserInNamespace(
        userId: UUID,
        namespaceId: UUID,
    ): List<Case> = findByParent(namespaceId)

    /** In tests all cases are visible to every user (no permission graph). */
    override fun findConcerningUser(userId: UUID): List<Case> = delegate.findAll()

    /** In tests all cases in the namespace are visible (no permission graph). */
    override fun findConcerningUserInNamespace(
        userId: UUID,
        namespaceId: UUID,
    ): List<Case> = delegate.findByParent(namespaceId)

    override fun findActiveByParentCaseId(parentCaseId: UUID): List<Case> =
        delegate.findAll().filter {
            it.parentCaseId == parentCaseId &&
                !it.metadata.removed &&
                it.status != io.whozoss.agentos.sdk.caseFlow.CaseStatus.KILLED &&
                it.status != io.whozoss.agentos.sdk.caseFlow.CaseStatus.ERROR
        }

    /** In tests, depth is not enforced — always returns 0. */
    override fun countAncestorDepth(caseId: UUID): Int = 0

    /** In tests, no graph edge is needed — no-op. */
    override fun linkParentToChild(parentCaseId: UUID, childCaseId: UUID): Unit = Unit
}
