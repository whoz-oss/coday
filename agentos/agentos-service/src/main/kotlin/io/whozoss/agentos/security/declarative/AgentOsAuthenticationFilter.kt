package io.whozoss.agentos.security.declarative

import io.whozoss.agentos.user.UserService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import mu.KLogging
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Servlet filter that bridges the existing AgentOS identity resolution
 * ([io.whozoss.agentos.security.SecurityService] → [UserService.getCurrentUser]) to the
 * Spring Security [SecurityContextHolder].
 *
 * Runs once per request, before any `@PreAuthorize` AOP can fire, so the [SecurityContextHolder]
 * is populated with an [AgentOsAuthentication] carrying the [io.whozoss.agentos.user.User] as
 * principal and `ROLE_SUPER_ADMIN` if applicable.
 *
 * If identity resolution fails (no auth header in `auth` mode, no OS username in `local` mode,
 * etc.), the filter logs the failure and lets the chain continue without populating the context —
 * downstream `@PreAuthorize` checks will then fail closed.
 *
 * **Auto-registration disabled** by [MethodSecurityConfig.agentOsAuthFilterRegistration]; this
 * bean is only wired into the chain via `addFilterBefore(...)` to prevent double execution.
 *
 * **Thread-local hygiene** : the [SecurityContextHolder] is cleared in a `finally` block so a
 * pooled thread does not leak the previous request's authentication to the next request, even
 * when the chain throws.
 *
 * **Error dispatches** : [shouldNotFilterErrorDispatch] returns `true` so the filter does not
 * re-run on the ERROR dispatch produced by an exception (the SecurityContext from the original
 * dispatch is irrelevant by then).
 */
@Component
class AgentOsAuthenticationFilter(
    private val userService: UserService,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        try {
            runCatching { userService.getCurrentUser() }
                .onSuccess { user ->
                    SecurityContextHolder.getContext().authentication = AgentOsAuthentication(user)
                }
                .onFailure { ex ->
                    // `Companion.` prefix avoids Kotlin/Java field clash with OncePerRequestFilter.logger
                    // (Kotlin compiler bug KT-56386 — see https://youtrack.jetbrains.com/issue/KT-56386)
                    Companion.logger.debug { "[AgentOsAuthenticationFilter] No identity for ${request.requestURI}: ${ex.message}" }
                }
            chain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    override fun shouldNotFilterErrorDispatch(): Boolean = true

    /**
     * Skip identity resolution for management/actuator endpoints.
     * These are infrastructure probes (health, metrics, info) that do not
     * carry an [io.whozoss.agentos.security.AuthSecurityService.X_EXTERNAL_USER_ID_HEADER]
     * header and do not need a Spring Security principal.
     */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI.startsWith("/management/")

    companion object : KLogging()
}
