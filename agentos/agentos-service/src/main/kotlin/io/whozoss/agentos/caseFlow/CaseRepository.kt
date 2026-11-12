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
    fun findAccessibleByUserInNamespace(
        userId: UUID,
        namespaceId: UUID,
    ): List<Case>

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
    fun findConcerningUserInNamespace(
        userId: UUID,
        namespaceId: UUID,
    ): List<Case>

    /**
     * Find all active (non-removed), non-terminal sub-cases whose [Case.parentCaseId] matches [parentCaseId].
     *
     */
    fun findActiveByParentCaseId(parentCaseId: UUID): List<Case>

    /**
     * Count the number of ancestor hops from [caseId] up through the parentCaseId chain.
     *
     * Returns 0 when [caseId] has no parent, 1 when it has one parent, etc.
     * Used to enforce a maximum delegation depth before creating a new sub-case.
     */
    fun countAncestorDepth(caseId: UUID): Int

    /**
     * Find all active, non-terminal descendants of [caseId] via the [:PARENT_OF] chain,
     * up to 10 levels deep, ordered leaves-first.
     *
     * Used by [io.whozoss.agentos.caseFlow.CaseService.killCase] to collect the full
     * subtree in one query instead of recursing through [findActiveByParentCaseId].
     */
    fun findActiveDescendants(caseId: UUID): List<Case>

    /**
     * Create the [:PARENT_OF] graph relationship from [parentCaseId] to [childCaseId].
     *
     * Called after persisting a sub-case so the ancestor-depth query can traverse the chain.
     */
    fun linkParentToChild(
        parentCaseId: UUID,
        childCaseId: UUID,
    )

    /**
     * Links the case [caseId] to the scheduled prompt [scheduledPromptId], unless it is already linked to one.
     *
     * A **targeted, write-once update** of the `scheduledPromptId` field alone, so that it neither reads nor
     * rewrites the rest of the case: a case started by an agent loop is created without its scheduled prompt, and
     * is linked right after, while its runtime may already be saving its status.
     *
     * Returns true when the link was written; false when the case does not exist, is soft-deleted, or already has
     * a scheduled prompt (which is never overwritten).
     *
     * **Known residual race.** [CaseServiceImpl] saves the whole case on each status change, from a copy it read
     * just before. If that read happens before this update and its save after, the save writes the old (null)
     * scheduled prompt back and the link is lost without notice. The window is a few milliseconds, and the loop
     * reports a failed link (`false`) but cannot see this one. The real fix is a targeted status update, which is
     * out of scope here.
     */
    fun assignScheduledPrompt(
        caseId: UUID,
        scheduledPromptId: UUID,
    ): Boolean
}
