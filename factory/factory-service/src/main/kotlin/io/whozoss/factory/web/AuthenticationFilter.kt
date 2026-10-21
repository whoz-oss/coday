package io.whozoss.factory.web

import io.whozoss.factory.config.FactoryProperties
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
 * [AuthenticatedUser] before any controller runs:
 *
 *  1. a valid `Authorization: Bearer <HS256 JWT>` gives that JWT's user;
 *  2. otherwise, with `factory.security.insecure-mode`, the fixed
 *     [INSECURE_USER_ID] user. A missing, expired, tampered or non-JWT bearer
 *     is treated like a missing one: insecure mode never answers 401, because
 *     an AgentOS Auth Setting may still hold a stale static token;
 *  3. otherwise `401 UNAUTHENTICATED`, answered here: the controller is never reached.
 *
 * No header can pick the user in insecure mode, and every user gets its tenant
 * scope from the same [MembershipResolver]. Controllers receive the result as a
 * non-null [AuthenticatedUser] parameter and contain no authentication logic.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class AuthenticationFilter(
    private val properties: FactoryProperties,
    private val tokenVerifier: TokenVerifier,
    private val membershipResolver: MembershipResolver,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !requiresAuthentication(request.requestURI)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val userId = bearerToken(request.getHeader("Authorization"))?.let(tokenVerifier::verify)
            ?: INSECURE_USER_ID.takeIf { properties.security.insecureMode }
        if (userId == null) {
            rejectUnauthenticated(response)
            return
        }
        request.setAttribute(ATTRIBUTE, AuthenticatedUser(userId, membershipResolver.resolveMembership(userId)))
        filterChain.doFilter(request, response)
    }

    private fun rejectUnauthenticated(response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.setHeader("Cache-Control", "no-store")
        response.writer.write(UNAUTHENTICATED_BODY)
    }

    private fun bearerToken(authorization: String?): String? =
        authorization?.let(BEARER_PATTERN::find)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    companion object {
        /** Request attribute exposing the [AuthenticatedUser] of the request. */
        const val ATTRIBUTE = "authenticatedUser"

        /** The fixed user of insecure mode. */
        const val INSECURE_USER_ID = "insecure-mode-user"

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

        private const val UNAUTHENTICATED_BODY =
            """{"error":{"code":"UNAUTHENTICATED","message":"Authentication required","details":null}}"""

        private val BEARER_PATTERN = Regex("^Bearer\\s+(.+)$", RegexOption.IGNORE_CASE)

        fun requiresAuthentication(path: String): Boolean =
            (path == "/api" || path.startsWith("/api/")) && PUBLIC_API_PATHS.none { isPublic(it, path) }

        private fun isPublic(entry: String, path: String): Boolean =
            when {
                entry.endsWith("/**") -> path.startsWith(entry.removeSuffix("**"))
                else -> path == entry
            }

        /** Read the user the filter attached to the request. */
        fun from(request: HttpServletRequest): AuthenticatedUser? = request.getAttribute(ATTRIBUTE) as? AuthenticatedUser
    }
}
