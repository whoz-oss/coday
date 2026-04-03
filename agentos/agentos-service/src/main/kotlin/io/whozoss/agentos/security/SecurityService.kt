package io.whozoss.agentos.security

/**
 * Resolves the raw identity reference for the current HTTP request.
 *
 * Two implementations are provided, selected by [agentos.security.mode]:
 * - [LocalSecurityService] (default): returns the OS username.
 * - [AuthSecurityService]: extracts the email from the Cloudflare CF_Authorization JWT,
 *   or falls back to the x-forwarded-email header. Throws 401 if no identity header is present.
 *
 * The current request is read internally from [org.springframework.web.context.request.RequestContextHolder],
 * so callers do not need to pass an [jakarta.servlet.http.HttpServletRequest].
 *
 * This service is intentionally narrow: it only resolves **who** is calling (a string identity),
 * without any knowledge of [io.whozoss.agentos.user.User] persistence. The caller is responsible
 * for mapping the identity to a domain user via [io.whozoss.agentos.user.UserService].
 *
 * When Spring Security is introduced, a third implementation can read from
 * [org.springframework.security.core.context.SecurityContextHolder] instead —
 * the interface and all callsites remain unchanged.
 */
interface SecurityService {
    /**
     * Returns the raw identity key for the current request (OS username, email address, etc.).
     * Throws an appropriate HTTP exception (401, 403, 500) when no identity can be resolved.
     */
    fun resolveCurrentIdentity(): String
}
