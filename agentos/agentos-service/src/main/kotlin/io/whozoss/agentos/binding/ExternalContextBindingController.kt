package io.whozoss.agentos.binding

import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.sdk.spi.ExternalContextBindingRegistrar
import mu.KLogging
import org.pf4j.PluginManager
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * Host transport that admits an out-of-band external execution binding for a case.
 *
 * The core owns the HTTP endpoint but knows nothing about the binding's semantics: this
 * controller extracts the opaque `X-External-Context-*` attributes and the shared credential
 * and forwards them to every [ExternalContextBindingRegistrar] discovered via PF4J (typically
 * an external context binding registrar plugin). The plugin validates the credential and
 * records the binding durably; how the bound capability then reaches the case run is the
 * plugin's own concern.
 *
 * The endpoint is fail-closed:
 * - unknown case -> 404;
 * - no registrar loaded (plugin absent) -> 404;
 * - no registrar accepted the binding (wrong/absent secret, invalid identity) -> 401.
 */
@RestController
@RequestMapping("/internal")
class ExternalContextBindingController(
    private val caseService: CaseService,
    private val pluginManager: PluginManager,
) {
    @PutMapping("/external-context/cases/{caseId}/bindings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun bind(
        @PathVariable caseId: UUID,
        @RequestHeader(name = SECRET_HEADER, required = false) externalContextSecret: String?,
        @RequestHeader(name = ATTEMPT_ID_HEADER, required = false) attemptId: String?,
        @RequestHeader(name = CAPABILITY_TOKEN_HEADER, required = false) capabilityToken: String?,
        @RequestHeader(name = RUNTIME_ID_HEADER, required = false) runtimeId: String?,
        @RequestHeader(name = AGENT_NAME_HEADER, required = false) agentName: String?,
        @RequestHeader(name = EXPIRES_AT_HEADER, required = false) expiresAt: String?,
    ) {
        val namespaceId =
            caseService.findById(caseId, false)?.namespaceId
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

        val registrars = pluginManager.getExtensions(ExternalContextBindingRegistrar::class.java)
        if (registrars.isEmpty()) {
            logger.warn { "External context binding requested for case $caseId but no registrar is loaded" }
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }

        val accepted =
            bind(
                registrars = registrars,
                caseId = caseId,
                namespaceId = namespaceId,
                credential = externalContextSecret,
                attributes = attributes(attemptId, capabilityToken, runtimeId, agentName),
                expiresAt = expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
            )
        if (!accepted) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
    }

    companion object : KLogging() {
        /**
         * Forwards a binding to the registrars, fail-closed.
         *
         * The resolution rule — first acceptance wins, a throwing registrar is a rejection
         * — belongs to [ExternalContextBindingRegistrar.registerFirst]. Kept in the SDK so
         * the host and any other caller share one semantic, and so this call site cannot
         * soften it.
         *
         * Shared with [ExternalContextBindingFilter], which binds at case creation.
         */
        fun bind(
            registrars: List<ExternalContextBindingRegistrar>,
            caseId: UUID,
            namespaceId: UUID,
            credential: String?,
            attributes: Map<String, String>,
            expiresAt: Instant?,
        ): Boolean =
            ExternalContextBindingRegistrar.registerFirst(
                registrars = registrars,
                caseId = caseId,
                namespaceId = namespaceId,
                credential = credential,
                attributes = attributes,
                expiresAt = expiresAt,
            ) { registrar, cause ->
                // Never log the credential or the attribute values — only who failed, and where.
                logger.warn(cause) { "External context binding registrar ${registrar::class.simpleName} failed for case $caseId" }
            }

        fun attributes(
            attemptId: String?,
            capabilityToken: String?,
            runtimeId: String?,
            agentName: String?,
        ): Map<String, String> =
            buildMap {
                attemptId?.takeIf { it.isNotBlank() }?.let { put(ATTRIBUTE_ATTEMPT_ID, it) }
                capabilityToken?.takeIf { it.isNotBlank() }?.let { put(ATTRIBUTE_CAPABILITY_TOKEN, it) }
                runtimeId?.takeIf { it.isNotBlank() }?.let { put(ATTRIBUTE_RUNTIME_ID, it) }
                agentName?.takeIf { it.isNotBlank() }?.let { put(ATTRIBUTE_AGENT_NAME, it) }
            }

        // ---------------------------------------------------------------------
        // Wire contract
        //
        // Declared once here and consumed by both admission paths (this endpoint and
        // [ExternalContextBindingFilter]), so the two cannot drift apart. The specs
        // deliberately spell the header names out as literals: they assert the wire
        // format itself, and would be worthless if they read it from the same constant
        // as the code under test.
        // ---------------------------------------------------------------------

        const val SECRET_HEADER = "X-External-Context-Secret"
        const val ATTEMPT_ID_HEADER = "X-External-Context-Attempt-Id"
        const val CAPABILITY_TOKEN_HEADER = "X-External-Context-Capability-Token"
        const val RUNTIME_ID_HEADER = "X-External-Context-Runtime-Id"
        const val AGENT_NAME_HEADER = "X-External-Context-Agent-Name"
        const val EXPIRES_AT_HEADER = "X-External-Context-Expires-At"

        const val ATTRIBUTE_ATTEMPT_ID = "attemptId"
        const val ATTRIBUTE_CAPABILITY_TOKEN = "capabilityToken"
        const val ATTRIBUTE_RUNTIME_ID = "runtimeId"
        const val ATTRIBUTE_AGENT_NAME = "agentName"
    }
}
