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
     * Permission rule for Case (owner-private, FR15, WZ-32167):
     * - User has a direct `[:ADMIN]` or `[:MEMBER]` relation on the case.
     * - Namespace ADMIN does NOT grant transitive visibility over all cases;
     *   every non-super-admin user (including Federation Admins and Designers)
     *   goes through this filter and sees only cases they directly own or were
     *   explicitly granted access to.
     *
     * Super-admin callers (`user.isAdmin == true`) are short-circuited upstream
     * in the controller and use [findByParent] directly.
     *
     * Implementations must exclude soft-deleted cases.
     */
    fun findAccessibleByUserInNamespace(userId: UUID, namespaceId: UUID): List<Case>
}
