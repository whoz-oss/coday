package io.whozoss.agentos.binding

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.spi.ExternalContextBindingRegistrar
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import mu.KLogging
import org.pf4j.PluginManager
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.time.Instant
import java.util.UUID

/**
 * Intercepts `POST /api/cases` so a case created with the `X-Factory-*` headers binds its
 * external step-result capability in the same round-trip.
 *
 * The Factory creates the case and immediately posts the brief; binding at creation time
 * (rather than on a later message) removes the race where the agent turn starts before the
 * capability is known. The filter is a strict no-op when no
 * [ExternalContextBindingRegistrar] is loaded or when the request carries no binding
 * attributes, so ordinary case creation is untouched.
 *
 * The created case id and namespace are read from the JSON response body — the host never
 * trusts a body-supplied id for a resource it did not itself return.
 */
@Component
class ExternalContextBindingFilter(
    private val pluginManager: PluginManager,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != HttpMethod.POST.name() || !request.requestURI.endsWith(CASES_PATH)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val registrars = pluginManager.getExtensions(ExternalContextBindingRegistrar::class.java)
        val attributes =
            ExternalContextBindingController.attributes(
                attemptId = request.getHeader(ATTEMPT_ID_HEADER),
                capabilityToken = request.getHeader(CAPABILITY_TOKEN_HEADER),
                runtimeId = request.getHeader(RUNTIME_ID_HEADER),
                agentName = request.getHeader(AGENT_NAME_HEADER),
            )
        if (registrars.isEmpty() || attributes.isEmpty()) {
            filterChain.doFilter(request, response)
            return
        }

        val credential = request.getHeader(SECRET_HEADER) ?: request.getHeader(SECRET_HEADER_LEGACY)
        val expiresAt = request.getHeader(EXPIRES_AT_HEADER)?.let { runCatching { Instant.parse(it) }.getOrNull() }

        val wrapped = ContentCachingResponseWrapper(response)
        filterChain.doFilter(request, wrapped)
        try {
            if (wrapped.status in 200..299) {
                bindFromResponse(wrapped.contentAsByteArray, registrars, credential, attributes, expiresAt)
            }
        } finally {
            // The wrapper buffers the body; it must be copied back or the client sees an
            // empty response.
            wrapped.copyBodyToResponse()
        }
    }

    private fun bindFromResponse(
        body: ByteArray,
        registrars: List<ExternalContextBindingRegistrar>,
        credential: String?,
        attributes: Map<String, String>,
        expiresAt: Instant?,
    ) {
        val node = runCatching { objectMapper.readTree(body) }.getOrNull() ?: return
        val caseId = node.get("id")?.asText()?.takeIf { it.isNotBlank() }?.let { parseUuid(it) } ?: return
        val namespaceId = node.get("namespaceId")?.asText()?.takeIf { it.isNotBlank() }?.let { parseUuid(it) } ?: return
        ExternalContextBindingController.bind(registrars, caseId, namespaceId, credential, attributes, expiresAt)
    }

    private fun parseUuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

    companion object : KLogging() {
        const val CASES_PATH = "/api/cases"
        const val ATTEMPT_ID_HEADER = "X-Factory-Attempt-Id"
        const val CAPABILITY_TOKEN_HEADER = "X-Factory-Capability-Token"
        const val RUNTIME_ID_HEADER = "X-Factory-Runtime-Id"
        const val AGENT_NAME_HEADER = "X-Factory-Agent-Name"
        const val EXPIRES_AT_HEADER = "X-Factory-Expires-At"
        const val SECRET_HEADER = "X-Factory-Agentos-Secret"
        const val SECRET_HEADER_LEGACY = "X-Factory-Secret"
    }
}
