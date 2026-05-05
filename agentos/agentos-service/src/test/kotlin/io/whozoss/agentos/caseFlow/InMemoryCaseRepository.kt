package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import java.util.UUID

/** Test-only in-memory implementation of [CaseRepository]. */
class InMemoryCaseRepository :
    CaseRepository,
    EntityRepository<Case, UUID> by InMemoryEntityRepository(
        parentIdExtractor = { it.namespaceId },
        comparator = compareBy { it.metadata.created },
    ) {
    override fun findAccessibleByUserInNamespace(userId: UUID, namespaceId: UUID): List<Case> =
        findByParent(namespaceId)
}
