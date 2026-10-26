package io.whozoss.factory.web

/**
 * Identity admitted by the Factory HTTP boundary.
 *
 * Authentication establishes only a user and an organization. A workstream is
 * business context and must be resolved explicitly from a namespace or a
 * persisted entity before constructing a TenantScope.
 */
data class AuthenticatedUser(
    val userId: String,
    val organizationId: String,
)
