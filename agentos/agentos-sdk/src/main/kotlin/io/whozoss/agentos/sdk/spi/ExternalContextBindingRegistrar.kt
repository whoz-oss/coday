package io.whozoss.agentos.sdk.spi

import org.pf4j.ExtensionPoint
import java.time.Instant
import java.util.UUID

/**
 * Generic SPI extension point that admits an out-of-band external execution binding
 * for a case.
 *
 * Some integrations bind an external capability (an opaque token, a step attempt
 * reference, a runtime identity, …) to a case before the case starts, so that the
 * integration can later submit an authoritative result through a channel that the
 * AgentOS runtime knows nothing about. The core only owns the *transport*: it
 * receives the binding facts (typically over HTTP at case creation) and forwards them,
 * verbatim and uninterpreted, to every registered registrar. Each registrar decides
 * whether it recognises the binding and whether to accept it.
 *
 * The core therefore never learns the semantics of the binding: the attribute keys and
 * values, the credential and the expiry are opaque to it. This keeps integration
 * specific types out of `agentos-sdk` and `agentos-service` while still giving the
 * host a stable, discoverable bridge to the plugin that owns the binding state.
 *
 * ### Safe default
 *
 * [register] defaults to `false` (rejected), so the contract is **fail-closed**: a
 * registrar that does not recognise a binding never accepts it, and behaviour is
 * unchanged when no registrar is registered.
 *
 * ### Exception handling
 *
 * Use [registerFirst] to consult registrars: one that throws is treated as a rejection,
 * so a faulty hook can never open a binding unintentionally.
 */
interface ExternalContextBindingRegistrar : ExtensionPoint {
    /**
     * Attempt to register an external execution binding for a case.
     *
     * @param caseId the case the binding applies to (already persisted).
     * @param namespaceId the namespace the case belongs to, resolved by the host.
     * @param credential the caller-supplied shared credential (for example a shared
     *   secret header); `null` when the transport carried none. The registrar is
     *   responsible for validating it.
     * @param attributes opaque binding attributes extracted from the transport, keyed by
     *   a registrar-understood name. The host neither defines nor interprets these keys.
     * @param expiresAt the binding expiry declared by the caller, or `null` when the
     *   transport carried none. The registrar may apply its own default.
     * @return `true` when the binding was accepted and durably recorded, `false`
     *   otherwise. Implementations must be fail-closed: any doubt returns `false`.
     */
    fun register(
        caseId: UUID,
        namespaceId: UUID,
        credential: String?,
        attributes: Map<String, String>,
        expiresAt: Instant?,
    ): Boolean = false

    companion object {
        /**
         * Offer a binding to each registrar in turn and return whether one accepted it.
         *
         * Lives in the SDK so the fail-closed rule is applied once, here, rather than
         * re-implemented at each call site.
         *
         * The first registrar to return `true` wins and the rest are not consulted — a
         * binding belongs to exactly one owner, and offering it twice would risk two
         * plugins recording state for the same case. A registrar that throws is treated
         * as a rejection and evaluation continues with the next one: one faulty plugin
         * must not prevent a legitimate owner from claiming its binding.
         *
         * Order is therefore significant. When several registrars are installed, it is
         * the discovery order (PF4J extension order) that decides who is offered the
         * binding first.
         *
         * @param onError notified when a registrar throws; must never throw. Diagnostics
         *   must never include [credential] or attribute values.
         */
        fun registerFirst(
            registrars: Iterable<ExternalContextBindingRegistrar>,
            caseId: UUID,
            namespaceId: UUID,
            credential: String?,
            attributes: Map<String, String>,
            expiresAt: Instant?,
            onError: (registrar: ExternalContextBindingRegistrar, cause: Throwable) -> Unit = { _, _ -> },
        ): Boolean =
            registrars.any { registrar ->
                try {
                    registrar.register(caseId, namespaceId, credential, attributes, expiresAt)
                } catch (e: Exception) {
                    onError(registrar, e)
                    false
                }
            }
    }
}
