package io.whozoss.agentos.security

import io.whozoss.agentos.user.User

/**
 * Resolves the authenticated user for the current request.
 *
 * Two implementations are provided, selected by [agentos.security.mode]:
 * - [LocalSecurityService] (default): resolves the OS username, auto-creates the User on first access.
 * - [AuthSecurityService]: decodes the Cloudflare JWT from CF_Authorization (or falls back
 *   to x-forwarded-email) and looks up the User by email (externalId). Auto-creates the User
 *   on first access if none exists. Throws 401 if no identity header is present.
 *
 * The current request is read internally from [org.springframework.web.context.request.RequestContextHolder],
 * so callers do not need to pass an [jakarta.servlet.http.HttpServletRequest].
 *
 * When Spring Security is introduced, a third implementation can read from
 * [org.springframework.security.core.context.SecurityContextHolder] instead —
 * the interface and all callsites remain unchanged.
 */
interface SecurityService {
    fun resolveCurrentUser(): User
}
