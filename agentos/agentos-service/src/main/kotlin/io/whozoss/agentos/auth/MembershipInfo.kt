package io.whozoss.agentos.auth

import io.whozoss.agentos.sdk.auth.NamespaceRole
import java.time.Instant

/**
 * Internal data class representing a user's membership in a namespace.
 *
 * Returned by [RoleRepository.findMembersOfNamespace]. The controller maps
 * this to [io.whozoss.agentos.namespace.MembershipResource] for the HTTP response.
 */
data class MembershipInfo(
    val userId: String,
    val role: NamespaceRole,
    val grantedAt: Instant,
    val grantedBy: String,
)
