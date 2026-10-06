package io.whozoss.agentos.plugins.factorybridge

import io.whozoss.agentos.sdk.spi.ExternalContextBindingRegistrar
import mu.KLogging
import org.pf4j.Extension
import java.time.Instant
import java.util.UUID

/**
 * PF4J extension that lets the AgentOS host bind a Factory step-result capability to a
 * case it has just created (or via the dedicated binding endpoint).
 *
 * The host owns the transport but knows nothing about the binding's semantics: it extracts
 * the opaque `X-External-Context-*` attributes and the shared credential and forwards them to every
 * registered [ExternalContextBindingRegistrar]. This extension validates the shared secret
 * (constant-time compare) and records the binding durably in
 * [FactoryStepResultBindingRegistry].
 *
 * Rejection is fail-closed: a blank configured secret, a wrong credential, a blank attempt
 * id or capability token all yield `false`.
 *
 * Recognised attribute keys (case-insensitive, produced by the host from the
 * `X-External-Context-*` headers):
 * - [ATTRIBUTE_ATTEMPT_ID] (`X-External-Context-Attempt-Id`)
 * - [ATTRIBUTE_CAPABILITY_TOKEN] (`X-External-Context-Capability-Token`)
 * - [ATTRIBUTE_RUNTIME_ID] (`X-External-Context-Runtime-Id`, defaults to the configured runtime id)
 * - [ATTRIBUTE_AGENT_NAME] (`X-External-Context-Agent-Name`, defaults to the wildcard so a
 *   capability bound before the agent identity is known can still be redeemed)
 */
@Extension
class FactoryBindingRegistrar
    @JvmOverloads
    constructor(
        private val services: () -> FactoryBridgeServices = { FactoryBridgePluginHolder.current },
    ) : ExternalContextBindingRegistrar {
        override fun register(
            caseId: UUID,
            namespaceId: UUID,
            credential: String?,
            attributes: Map<String, String>,
            expiresAt: Instant?,
        ): Boolean {
            val resolved = services()
            val controller =
                FactoryStepResultBindingController(
                    registry = resolved.stepResultBindings,
                    // The host has already resolved and persisted the case, so the
                    // namespace it hands us is authoritative.
                    caseNamespace = { requested -> namespaceId.takeIf { requested == caseId } },
                    secret = resolved.config.secret.orEmpty(),
                )
            val request =
                FactoryStepResultBindingRequest(
                    namespaceId = namespaceId,
                    agentName = attributes[ATTRIBUTE_AGENT_NAME]?.takeIf { it.isNotBlank() } ?: FACTORY_AGENT_WILDCARD,
                    attemptId = attributes[ATTRIBUTE_ATTEMPT_ID].orEmpty(),
                    runtimeId = attributes[ATTRIBUTE_RUNTIME_ID]?.takeIf { it.isNotBlank() } ?: resolved.config.runtimeId,
                    capabilityToken = attributes[ATTRIBUTE_CAPABILITY_TOKEN].orEmpty(),
                    expiresAt = expiresAt ?: Instant.now().plusSeconds(resolved.config.bindingTtlSeconds),
                )
            val outcome = controller.bind(caseId, credential, request)
            if (outcome != FactoryBindingOutcome.Bound) {
                logger.warn { "Factory binding rejected for case=$caseId: $outcome" }
            }
            return outcome == FactoryBindingOutcome.Bound
        }

        companion object : KLogging() {
            const val ATTRIBUTE_ATTEMPT_ID = "attemptId"
            const val ATTRIBUTE_CAPABILITY_TOKEN = "capabilityToken"
            const val ATTRIBUTE_RUNTIME_ID = "runtimeId"
            const val ATTRIBUTE_AGENT_NAME = "agentName"
        }
    }
