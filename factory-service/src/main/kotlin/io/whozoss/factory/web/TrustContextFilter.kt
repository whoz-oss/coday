package io.whozoss.factory.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Resolves the [TrustContext] once per request and exposes it as a request
 * attribute (`trustContext`), so controllers/use cases never re-extract identity.
 *
 * Runs after the [CorrelationIdFilter] so the resolved correlation id is part of
 * the context.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class TrustContextFilter(
    private val extractor: TrustContextExtractor,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val correlationId = request.getAttribute(CorrelationIdFilter.ATTRIBUTE) as? String
        request.setAttribute(ATTRIBUTE, extractor.extract(request, correlationId))
        filterChain.doFilter(request, response)
    }

    companion object {
        /** Request attribute exposing the resolved [TrustContext]. */
        const val ATTRIBUTE = "trustContext"

        /** Read the [TrustContext] previously attached to the request. */
        fun from(request: HttpServletRequest): TrustContext? = request.getAttribute(ATTRIBUTE) as? TrustContext
    }
}
