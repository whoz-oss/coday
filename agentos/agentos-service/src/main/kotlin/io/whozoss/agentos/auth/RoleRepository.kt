package io.whozoss.agentos.auth

import io.whozoss.agentos.sdk.auth.CaseRole
import io.whozoss.agentos.sdk.auth.NamespaceRole
import java.time.Instant

/**
 * Repository for managing role assignments on Namespaces and Cases.
 *
 * Roles are stored as graph relationships:
 * - `(User)-[:MEMBER_OF {role, grantedAt, grantedBy}]->(Namespace)`
 * - `(User)-[:PARTICIPANT_IN {role, grantedAt, grantedBy, allowedTools?, toolRestrictionMode?}]->(Case)`
 *
 * The `isRoot` flag is stored directly on the User node.
 *
 * All IDs are [String] to align with Neo4j node `@Id` convention used throughout
 * the codebase (UUID → String conversion happens in the domain layer).
 */
interface RoleRepository {

    // -------------------------------------------------------------------------
    // Root status
    // -------------------------------------------------------------------------

    /**
     * Check whether the user is a root (super-admin).
     */
    fun isRoot(userId: String): Boolean

    /**
     * Grant or revoke root status for a user.
     */
    fun setRoot(userId: String, isRoot: Boolean)

    // -------------------------------------------------------------------------
    // Namespace roles (MEMBER_OF)
    // -------------------------------------------------------------------------

    /**
     * Find the namespace role for a user in a specific namespace.
     * Returns `null` if the user has no role in that namespace.
     */
    fun findNamespaceRole(userId: String, namespaceId: String): NamespaceRole?

    /**
     * Assign (or update) a namespace role for a user.
     * Uses MERGE semantics — existing role is overwritten.
     */
    fun assignNamespaceRole(userId: String, namespaceId: String, role: NamespaceRole, grantedBy: String)

    /**
     * Remove the namespace role for a user.
     */
    fun removeNamespaceRole(userId: String, namespaceId: String)

    /**
     * Find all members of a namespace with their roles and metadata.
     * Returns a list of [MembershipInfo] for each MEMBER_OF relationship.
     */
    fun findMembersOfNamespace(namespaceId: String): List<MembershipInfo>

    /**
     * Count the number of users with the OWNER role in a namespace.
     * Used to enforce the "last OWNER" protection rule.
     */
    fun countOwnersInNamespace(namespaceId: String): Int

    /**
     * Find all namespace IDs where the user has a MEMBER_OF relationship.
     * Returns the IDs via a single graph query (no O(N×P) in-memory filtering).
     */
    fun findNamespaceIdsForUser(userId: String): Set<String>

    // -------------------------------------------------------------------------
    // Case roles (PARTICIPANT_IN)
    // -------------------------------------------------------------------------

    /**
     * Find the case role for a user in a specific case.
     * Returns `null` if the user has no role in that case.
     */
    fun findCaseRole(userId: String, caseId: String): CaseRole?

    /**
     * Assign (or update) a case role for a user.
     * Uses MERGE semantics — existing role is overwritten.
     */
    fun assignCaseRole(userId: String, caseId: String, role: CaseRole, grantedBy: String)

    /**
     * Remove the case role for a user.
     */
    fun removeCaseRole(userId: String, caseId: String)

    /**
     * Find all case IDs accessible to a user within a namespace.
     * Traverses `(User)-[:MEMBER_OF]->(Namespace)<-[:BELONGS_TO]-(Case)` in a
     * single graph query (no O(N×P) in-memory filtering).
     */
    fun findAccessibleCaseIdsForUser(userId: String, namespaceId: String): Set<String>
}
