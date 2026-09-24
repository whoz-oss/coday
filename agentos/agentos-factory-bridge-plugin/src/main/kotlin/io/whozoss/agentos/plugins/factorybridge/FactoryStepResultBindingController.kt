package io.whozoss.agentos.plugins.factorybridge

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Payload accepted by the Factory when it binds a step-result capability to a case.
 *
 * All identities are supplied by the Factory over an authenticated channel — never by the
 * model or the case user.
 */
data class FactoryStepResultBindingRequest(
    val namespaceId: UUID,
    val agentName: String,
    val attemptId: String,
    val runtimeId: String,
    val capabilityToken: String,
    val expiresAt: Instant,
)

/**
 * Outcome of a [FactoryStepResultBindingController.bind] call.
 *
 * The plugin does not own an HTTP server; the AgentOS host exposes the transport (the
 * original Spring `@RestController`) and maps these outcomes onto status codes. Keeping the
 * decision logic framework-free makes it unit-testable and transport-agnostic.
 */
sealed interface FactoryBindingOutcome {
    data object Bound : FactoryBindingOutcome

    data object Unauthorized : FactoryBindingOutcome

    data object NotFound : FactoryBindingOutcome

    data object Conflict : FactoryBindingOutcome
}

/**
 * Trust-critical admission logic for the Factory step-result binding endpoint.
 *
 * Validates the shared secret with a constant-time comparison, confirms the target case
 * exists and matches the declared namespace, then records the volatile capability in the
 * [FactoryStepResultBindingRegistry]. Any inconsistency is rejected.
 *
 * @param caseNamespace resolves the namespace of a case, or null when the case is unknown.
 * @param secret the pre-shared secret; an empty secret disables the endpoint (fail-closed).
 */
class FactoryStepResultBindingController(
    private val registry: FactoryStepResultBindingRegistry,
    private val caseNamespace: (UUID) -> UUID?,
    private val secret: String,
) {
    fun bind(
        caseId: UUID,
        suppliedSecret: String?,
        request: FactoryStepResultBindingRequest,
    ): FactoryBindingOutcome {
        if (secret.isBlank() || suppliedSecret == null || !MessageDigest.isEqual(secret.toByteArray(), suppliedSecret.toByteArray())) {
            return FactoryBindingOutcome.Unauthorized
        }
        val namespaceId = caseNamespace(caseId) ?: return FactoryBindingOutcome.NotFound
        if (namespaceId != request.namespaceId || request.agentName.isBlank() || request.attemptId.isBlank() || request.runtimeId.isBlank()) {
            return FactoryBindingOutcome.Conflict
        }
        return runCatching {
            registry.bind(
                FactoryStepResultBinding(
                    caseId = caseId,
                    namespaceId = request.namespaceId,
                    agentName = request.agentName,
                    attemptId = request.attemptId,
                    runtimeId = request.runtimeId,
                    capabilityToken = request.capabilityToken,
                    expiresAt = request.expiresAt,
                ),
            )
            FactoryBindingOutcome.Bound
        }.getOrElse { FactoryBindingOutcome.Conflict }
    }
}
