package io.whozoss.agentos.auth

import io.whozoss.agentos.sdk.auth.CaseRole
import io.whozoss.agentos.sdk.auth.NamespaceRole
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of [RoleRepository].
 *
 * Active when `agentos.persistence.mode` is absent, `in-memory`, or any value
 * other than `neo4j` or `embedded-neo4j`. This is the default fallback used by
 * the openapi spec generation task and lightweight local runs.
 *
 * All data is stored in [ConcurrentHashMap] and lost on restart.
 */
@Repository
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' != 'neo4j' " +
        "and '\${agentos.persistence.mode:in-memory}' != 'embedded-neo4j'",
)
class InMemoryRoleRepository : RoleRepository {

    private val rootUsers = ConcurrentHashMap.newKeySet<String>()
    private val namespaceMemberships = ConcurrentHashMap<String, MembershipInfo>()
    private val caseRoles = ConcurrentHashMap<String, CaseRole>()

    // -------------------------------------------------------------------------
    // Root status
    // -------------------------------------------------------------------------

    override fun isRoot(userId: String): Boolean = userId in rootUsers

    override fun setRoot(userId: String, isRoot: Boolean) {
        when {
            isRoot -> rootUsers.add(userId)
            else -> rootUsers.remove(userId)
        }
    }

    // -------------------------------------------------------------------------
    // Namespace roles (MEMBER_OF)
    // -------------------------------------------------------------------------

    override fun findNamespaceRole(userId: String, namespaceId: String): NamespaceRole? =
        namespaceMemberships[namespaceRoleKey(userId, namespaceId)]?.role

    override fun assignNamespaceRole(userId: String, namespaceId: String, role: NamespaceRole, grantedBy: String) {
        namespaceMemberships[namespaceRoleKey(userId, namespaceId)] = MembershipInfo(
            userId = userId,
            role = role,
            grantedAt = Instant.now(),
            grantedBy = grantedBy,
        )
    }

    override fun removeNamespaceRole(userId: String, namespaceId: String) {
        namespaceMemberships.remove(namespaceRoleKey(userId, namespaceId))
    }

    override fun findMembersOfNamespace(namespaceId: String): List<MembershipInfo> =
        namespaceMemberships.entries
            .filter { it.key.endsWith(":$namespaceId") }
            .map { it.value }

    override fun countOwnersInNamespace(namespaceId: String): Int =
        findMembersOfNamespace(namespaceId).count { it.role == NamespaceRole.OWNER }

    override fun findNamespaceIdsForUser(userId: String): Set<String> =
        namespaceMemberships.keys
            .filter { it.startsWith("$userId:") }
            .map { it.substringAfter(":") }
            .toSet()

    // -------------------------------------------------------------------------
    // Case roles (PARTICIPANT_IN)
    // -------------------------------------------------------------------------

    override fun findCaseRole(userId: String, caseId: String): CaseRole? =
        caseRoles[caseRoleKey(userId, caseId)]

    override fun assignCaseRole(userId: String, caseId: String, role: CaseRole, grantedBy: String) {
        caseRoles[caseRoleKey(userId, caseId)] = role
    }

    override fun removeCaseRole(userId: String, caseId: String) {
        caseRoles.remove(caseRoleKey(userId, caseId))
    }

    override fun findAccessibleCaseIdsForUser(userId: String, namespaceId: String): Set<String> =
        caseRoles.keys
            .filter { it.startsWith("$userId:") }
            .map { it.substringAfter(":") }
            .toSet()

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun namespaceRoleKey(userId: String, namespaceId: String): String = "$userId:$namespaceId"

    private fun caseRoleKey(userId: String, caseId: String): String = "$userId:$caseId"
}
