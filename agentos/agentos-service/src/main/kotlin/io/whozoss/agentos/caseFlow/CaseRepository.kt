package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Repository for [Case] persistence.
 *
 * Parent type is UUID representing the namespaceId.
 */
interface CaseRepository : EntityRepository<Case, UUID> {
    /**
     * Find cases in a namespace that [userId] is allowed to see.
     *
     * Permission rule for Case (owner-private, FR15):
     * - User has a direct `[:ADMIN]` or `[:MEMBER]` relation on the case, or
     * - User has a direct `[:ADMIN]` relation on the parent namespace (transitive
     *   ADMIN). Namespace MEMBER does NOT grant transitive READ on cases.
     *
     * Super-admin callers must be handled upstream (controller short-circuits
     * and uses [findByParent] directly to avoid going through this filter).
     *
     * Implementations must exclude soft-deleted cases.
     */
    fun findAccessibleByUserInNamespace(userId: UUID, namespaceId: UUID): List<Case>

    /**
     * Find all cases concerning [userId] across every namespace.
     *
     * A case concerns a user when they have a direct ADMIN or MEMBER relation on it.
     * Namespace-level ADMIN is intentionally excluded so that a namespace admin only
     * sees their own threads, not every case in the namespace.
     * Implementations must exclude soft-deleted cases.
     */
    fun findConcerningUser(userId: UUID): List<Case>

    /**
     * Find all cases concerning [userId] scoped to a single [namespaceId].
     *
     * Same permission rule as [findConcerningUser] (direct ADMIN or MEMBER on the case),
     * but restricted to the given namespace.
     * Implementations must exclude soft-deleted cases.
     */
    fun findConcerningUserInNamespace(userId: UUID, namespaceId: UUID): List<Case>

    /**
     * Find all active (non-removed) sub-cases whose [Case.parentCaseId] matches [parentCaseId].
     *
     * Used by [io.whozoss.agentos.caseFlow.CaseService.killCase] to propagate kill signals
     * to sub-cases created by delegation. Does not filter by status — a sub-case may be
     * IDLE, RUNNING, or PENDING and still needs to be killed when its parent is killed.
     */
    fun findActiveByParentCaseId(parentCaseId: UUID): List<Case>
}
