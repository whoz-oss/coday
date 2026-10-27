package io.whozoss.factory.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Enforces `X-Correlation-Id` on every request/response pair.
 *
 * An inbound header is propagated (trimmed, capped at 256 chars); otherwise a
 * trace id of the form `coday-corr-<uuid>` is minted. The resolved value is
 * stored as a request attribute *and* echoed on the response, so a caller can
 * trace one request end-to-end — exactly like the Node `send` helper.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val correlationId = resolveCorrelationId(request.getHeader(HEADER))
        request.setAttribute(ATTRIBUTE, correlationId)
        response.setHeader(HEADER, correlationId)
        filterChain.doFilter(request, response)
    }

    companion object {
        /** Canonical header used for request/response correlation. */
        const val HEADER = "X-Correlation-Id"

        /** Request attribute exposing the resolved correlation id. */
        const val ATTRIBUTE = "correlationId"

        private const val MAX_LENGTH = 256

        fun generateCorrelationId(): String = "coday-corr-${UUID.randomUUID()}"

        fun resolveCorrelationId(raw: String?): String {
            val value = raw?.trim()
            return if (!value.isNullOrEmpty()) value.take(MAX_LENGTH) else generateCorrelationId()
        }
    }
}
