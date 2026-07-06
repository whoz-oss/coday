package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.InMemoryEntityRepository
import java.util.UUID

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

    /**
     * In-memory BFS implementation of [findActiveDescendants].
     *
     * Traverses [Case.parentCaseId] links up to 10 levels deep and returns
     * active, non-terminal descendants leaves-first (deepest level first).
     * Used in unit tests that exercise [io.whozoss.agentos.caseFlow.CaseServiceImpl.killSingleCase].
     */
    override fun findActiveDescendants(caseId: UUID): List<Case> {
        val all = delegate.findAll()
        val result = mutableListOf<List<Case>>()
        var frontier = listOf(caseId)
        repeat(10) {
            val nextLevel =
                all.filter { c ->
                    c.parentCaseId in frontier &&
                        !c.metadata.removed &&
                        c.status != io.whozoss.agentos.sdk.caseFlow.CaseStatus.KILLED &&
                        c.status != io.whozoss.agentos.sdk.caseFlow.CaseStatus.ERROR
                }
            if (nextLevel.isEmpty()) return@repeat
            result.add(0, nextLevel) // prepend so deeper levels come first
            frontier = nextLevel.map { it.id }
        }
        return result.flatten()
    }

    /** In tests, depth is not enforced — always returns 0. */
    override fun countAncestorDepth(caseId: UUID): Int = 0

    /** In tests, no graph edge is needed — no-op. */
    override fun linkParentToChild(
        parentCaseId: UUID,
        childCaseId: UUID,
    ): Unit = Unit
}
