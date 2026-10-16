package io.whozoss.factory.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Confines the two static service credentials to the routes they exist for.
 *
 * The Factory routes authorize from the verified tenant scope and do not check
 * individual scopes, so a statically authenticated service identity with a tenant
 * membership could otherwise reach every route. This filter closes that gap at
 * the HTTP boundary, before any controller runs:
 *
 *  - the worker submission credential may only `POST` the step-result route;
 *  - the `FACTORY_WORKSTREAM` credential may only reach the dedicated
 *    `/api/factory/workstream-service/` facade, which enforces its own
 *    per-operation permission.
 *
 * Everything else is refused with `403 SERVICE_ROUTE_FORBIDDEN`. Other
 * authentication methods are left untouched.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class StaticServiceRouteGuardFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val method = TrustContextFilter.from(request)?.authenticationMethod
        val allowed = when (method) {
            TrustContext.AUTH_STATIC_SERVICE_BEARER -> workerRouteAllowed(request)
            TrustContext.AUTH_STATIC_WORKSTREAM_BEARER -> workstreamRouteAllowed(request)
            else -> true
        }
        if (allowed) {
            filterChain.doFilter(request, response)
            return
        }
        response.status = HttpServletResponse.SC_FORBIDDEN
        response.contentType = "application/json"
        response.setHeader("Cache-Control", "no-store")
        response.writer.write(
            """{"error":{"code":"SERVICE_ROUTE_FORBIDDEN","message":"This service credential cannot reach this route","details":null}}""",
        )
    }

    private fun workerRouteAllowed(request: HttpServletRequest): Boolean =
        request.method == "POST" && request.requestURI == WORKER_ROUTE

    private fun workstreamRouteAllowed(request: HttpServletRequest): Boolean {
        val uri = request.requestURI
        if (request.method != "GET" && request.method != "POST") return false
        if (!WORKSTREAM_ROUTES.matches(uri)) return false
        // Each segment must start with an alphanumeric (already enforced), which
        // also excludes `.` and `..`; reject encoded aliases explicitly as well.
        return !uri.contains('%') && uri.split('/').none { it == "." || it == ".." }
    }

    companion object {
        const val WORKER_ROUTE = "/api/factory/agent-step-results"
        const val WORKSTREAM_PREFIX = "/api/factory/workstream-service/"

        /** Literal characters only: no encoded alias, no dot segment, no double slash. */
        private val WORKSTREAM_ROUTES = Regex(
            "^/api/factory/workstream-service/(?:[A-Za-z0-9][A-Za-z0-9._:@-]*)(?:/[A-Za-z0-9][A-Za-z0-9._:@-]*)*$",
        )
    }
}
