package io.whozoss.agentos.security.declarative

import io.whozoss.agentos.user.User
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority

/**
 * Custom Spring Security [org.springframework.security.core.Authentication] implementation
 * that carries the AgentOS [User] as the principal.
 *
 * Key design choice: `getName()` returns the user's UUID as a String, NOT the principal's
 * `toString()`. This is what [AgentOsPermissionEvaluator] reads to identify the caller,
 * and what `@PreAuthorize("#id == authentication.name")` compares against in .
 *
 * Authorities are derived **strictly** from [User.isAdmin] inside the constructor — there is
 * no externally-supplied authorities parameter, so a caller cannot fabricate a token with
 * `ROLE_SUPER_ADMIN` for a non-admin user.
 */
class AgentOsAuthentication(
    private val user: User,
) : AbstractAuthenticationToken(computeAuthorities(user)) {

    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): Any = user

    /**
     * Returns the User's UUID as a String — this is the value [AgentOsPermissionEvaluator]
     * reads to call [io.whozoss.agentos.permissions.PermissionService.hasPermission].
     */
    override fun getName(): String = user.id.toString()

    companion object {
        private fun computeAuthorities(user: User): List<GrantedAuthority> =
            if (user.isAdmin) listOf(SimpleGrantedAuthority("ROLE_SUPER_ADMIN")) else emptyList()
    }
}
