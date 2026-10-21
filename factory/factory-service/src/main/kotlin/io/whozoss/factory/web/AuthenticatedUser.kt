package io.whozoss.factory.web

import io.whozoss.factory.persistence.TenantScope

/**
 * The one identity type of the Factory HTTP boundary.
 *
 * It exists only for a request that [AuthenticationFilter] admitted: either the
 * holder of a valid HS256 JWT, or the fixed insecure-mode user when
 * `factory.security.insecure-mode` is enabled. There is no role, scope or
 * principal type: every authenticated user can do everything.
 *
 * [scope] is the tenant (organization + workstream) the user belongs to, resolved
 * once at authentication time by the [MembershipResolver].
 */
data class AuthenticatedUser(
    val userId: String,
    val scope: TenantScope,
)
