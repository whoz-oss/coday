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
}
