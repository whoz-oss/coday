package io.whozoss.factory.web

import io.whozoss.factory.web.security.IdentityResolver
import io.whozoss.factory.web.security.IdentityResult
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * The single authentication point of the Factory HTTP API.
 *
 * Every request under the `/api` prefix (but the [PUBLIC_API_PATHS]) is resolved to an
 * [AuthenticatedUser] before any controller runs. WHO the caller is comes from the
 * [IdentityResolver] selected by `factory.security.mode` (`FACTORY_SECURITY_MODE`, the
 * variable AgentOS reads too, see [io.whozoss.factory.web.security.SecurityConfiguration]):
 *
 *  - `local` (default): the OS user running Factory. Headers and bearers are ignored, so
 *    a stale token (an AgentOS Auth Setting may still hold one) never produces a 401. A
 *    system account (root, daemon, ...) is answered `403 FORBIDDEN_SYSTEM_ACCOUNT`, an
 *    undeterminable OS user `500 OS_USERNAME_UNAVAILABLE`.
 *  - `auth`: a Factory HS256 JWT, else `X-External-User-Id`, `Cf-Access-Jwt-Assertion`,
 *    an unverified bearer's `preferred_username` or `x-forwarded-email`, in that order;
 *    otherwise `401 UNAUTHENTICATED`.
 *
 * Whatever the mode, every user gets its tenant scope from the same
 * [MembershipResolver], and the refusal is answered here: the controller is never
 * reached. Controllers receive the result as a non-null [AuthenticatedUser] parameter
 * and contain no authentication logic.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class AuthenticationFilter(
    private val identityResolver: IdentityResolver,
    private val membershipResolver: MembershipResolver,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !requiresAuthentication(request.requestURI)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        when (val identity = identityResolver.resolve(request)) {
            is IdentityResult.Authenticated -> {
                val userId = identity.userId
                request.setAttribute(ATTRIBUTE, AuthenticatedUser(userId, membershipResolver.resolveMembership(userId)))
                filterChain.doFilter(request, response)
            }
            IdentityResult.Unauthenticated ->
                reject(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required")
            is IdentityResult.Forbidden ->
                reject(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN_SYSTEM_ACCOUNT", identity.message)
            is IdentityResult.Unavailable ->
                reject(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "OS_USERNAME_UNAVAILABLE", identity.message)
        }
    }

    private fun reject(response: HttpServletResponse, status: Int, code: String, message: String) {
        response.status = status
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.setHeader("Cache-Control", "no-store")
        response.writer.write("""{"error":{"code":"$code","message":"${jsonEscape(message)}","details":null}}""")
    }

    companion object {
        /** Request attribute exposing the [AuthenticatedUser] of the request. */
        const val ATTRIBUTE = "authenticatedUser"

        /**
         * Routes under the `/api` prefix answered without authentication. An entry
         * ending in a double star is a prefix match. Everything outside the `/api`
         * prefix (the Cockpit SPA and its assets, the OpenAPI document, swagger,
         * actuator) is never guarded.
         */
        val PUBLIC_API_PATHS: List<String> = listOf(
            "/api/config",
            "/api/factory/openapi/**",
        )

        fun requiresAuthentication(path: String): Boolean =
            (path == "/api" || path.startsWith("/api/")) && PUBLIC_API_PATHS.none { isPublic(it, path) }

        private fun isPublic(entry: String, path: String): Boolean =
            when {
                entry.endsWith("/**") -> path.startsWith(entry.removeSuffix("**"))
                else -> path == entry
            }

        /** The message may carry an OS username: escape it so the envelope stays valid JSON. */
        private fun jsonEscape(text: String): String =
            buildString {
                text.forEach { c ->
                    when {
                        c == '"' -> append("\\\"")
                        c == '\\' -> append("\\\\")
                        c < ' ' -> append("\\u%04x".format(c.code))
                        else -> append(c)
                    }
                }
            }

        /** Read the user the filter attached to the request. */
        fun from(request: HttpServletRequest): AuthenticatedUser? = request.getAttribute(ATTRIBUTE) as? AuthenticatedUser
    }
}
