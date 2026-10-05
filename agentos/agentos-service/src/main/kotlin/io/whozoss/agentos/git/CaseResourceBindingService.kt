package io.whozoss.agentos.git

import io.whozoss.agentos.entity.EntityService
import java.util.UUID

/**
 * Business operations over [CaseResourceBinding].
 *
 * No REST controller: a binding is internal state of a case family, surfaced to clients through
 * the case's Git projection.
 */
interface CaseResourceBindingService : EntityService<CaseResourceBinding, UUID> {
    /** The active binding owned by [rootCaseId], or null when that family is not equipped. */
    fun findByRootCaseId(rootCaseId: UUID): CaseResourceBinding?

    /** Active bindings ordered by creation time and ID, strictly after [after], capped by [limit]. */
    fun findByStatusIn(
        statuses: Collection<CaseResourceStatus>,
        limit: Int,
        after: CaseResourceBindingCursor? = null,
    ): List<CaseResourceBinding>

    /**
     * Record progress of a preparation attempt, re-reading the row before writing it back.
     *
     * Writers hold the family's lock, and the row's version refuses a write based on an older read,
     * so a concurrent write fails instead of being lost.
     */
    fun markStatus(
        id: UUID,
        status: CaseResourceStatus,
        failureReason: String? = null,
    ): CaseResourceBinding
}
