package io.whozoss.agentos.security

import io.whozoss.agentos.user.User
import jakarta.servlet.http.HttpServletRequest

/**
 * Resolves the authenticated user from an incoming HTTP request.
 *
 * Two implementations are provided, selected by [agentos.security.mode]:
 * - [LocalSecurityService] (default): resolves the OS username, auto-creates the User on first access.
 * - [AuthSecurityService]: decodes the Cloudflare JWT from CF_Authorization and looks up
 *   the User by email (externalId). Throws 401 if the header is absent or the user is unknown.
 *
 * When Spring Security is introduced, a third implementation can read from SecurityContextHolder
 * without changing this interface or any callsite.
 */
interface SecurityService {
    fun resolveCurrentUser(request: HttpServletRequest): User
}
