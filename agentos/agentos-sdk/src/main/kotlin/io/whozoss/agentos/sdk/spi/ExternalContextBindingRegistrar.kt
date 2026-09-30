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
 * Unexpected exceptions thrown by a registrar are caught and logged by the caller and
 * treated as a rejection, so a faulty hook can never open a binding unintentionally.
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
     * @param attributes opaque binding attributes extracted from the transport (for
     *   example the `X-Factory-*` headers), keyed by a registrar-understood name.
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
}
