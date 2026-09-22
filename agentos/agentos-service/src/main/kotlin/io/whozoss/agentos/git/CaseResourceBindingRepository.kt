package io.whozoss.agentos.git

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Persistence for [CaseResourceBinding].
 *
 * [findByParent] lists a namespace's bindings; the per-family lookup is
 * [findByRootCaseId], which is the read every equipped code path uses.
 */
interface CaseResourceBindingRepository : EntityRepository<CaseResourceBinding, UUID> {
    /** The active binding owned by [rootCaseId], or null when that family is not equipped. */
    fun findByRootCaseId(rootCaseId: UUID): CaseResourceBinding?

    /** Active bindings in one of [statuses], oldest first, capped by [limit]. */
    fun findByStatusIn(
        statuses: Collection<CaseResourceStatus>,
        limit: Int,
    ): List<CaseResourceBinding>
}
