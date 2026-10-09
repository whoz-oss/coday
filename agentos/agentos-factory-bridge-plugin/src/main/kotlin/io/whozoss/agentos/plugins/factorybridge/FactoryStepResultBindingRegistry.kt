package io.whozoss.agentos.plugins.factorybridge

import io.whozoss.agentos.plugins.factorybridge.persistence.FactoryBridgeStateStore
import io.whozoss.agentos.plugins.factorybridge.persistence.FactoryStepResultBindingState
import mu.KLogging
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Case-scoped Factory result capability binding.
 *
 * @property capabilityToken opaque bearer token issued by the Factory for exactly one
 *   step attempt; never derived from model input.
 * @property agentName the agent identity allowed to redeem the binding. The wildcard
 *   `"*"` matches any agent of the case: used when the Factory binds a capability at case
 *   creation, before the agent identity is known.
 */
data class FactoryStepResultBinding(
    val caseId: UUID,
    val namespaceId: UUID,
    val agentName: String,
    val attemptId: String,
    val runtimeId: String,
    val capabilityToken: String,
    val expiresAt: Instant,
    val leased: AtomicBoolean = AtomicBoolean(false),
)

/** Agent-name wildcard: any agent of the case may redeem the binding. */
const val FACTORY_AGENT_WILDCARD = "*"

/**
 * Registry of Factory step-result bindings with **durable** backing state.
 *
 * The registry keeps an in-memory working set for cheap lookups and single-flight CAS,
 * but mirrors every mutation (bind, lease acquire/release, acknowledge, invalidate,
 * removal) into a restart-safe [FactoryBridgeStateStore]. After an AgentOS restart the
 * registry is reconstructed from the store, so an unfinished binding and its lease
 * survive.
 *
 * An expired binding is deliberately **kept**: [acquire] with `allowExpired` is how
 * [io.whozoss.agentos.plugins.factorybridge.FactoryStepResultCapabilityRefresher] renews a
 * capability that lapsed mid-attempt. Purging on expiry would make renewal impossible and
 * turn a recoverable lapse into a lost result. Removal happens on acknowledge, invalidate,
 * or when the case reaches a terminal status.
 *
 * By design the registry keeps no durable state when constructed without a store (unit
 * tests): a restart then loses every capability and therefore fails closed.
 *
 * ### Fail-closed semantics
 *
 * A binding is only usable while it is unexpired and (for redemption) unleased. A case
 * that reaches a terminal status invalidates its binding; the write-through store removes
 * it durably so a stale capability can never be resurrected by a restart.
 */
class FactoryStepResultBindingRegistry(
    private val clock: Clock = Clock.systemUTC(),
    private val store: FactoryBridgeStateStore? = null,
) {
    companion object : KLogging()

    private val bindings = ConcurrentHashMap<UUID, FactoryStepResultBinding>()

    init {
        // Re-hydrate the working set from the durable store so an in-flight binding (and
        // its lease) survives an AgentOS restart.
        store?.bindings()?.forEach { state ->
            bindings[state.caseId] = toBinding(state)
        }
    }

    fun bind(binding: FactoryStepResultBinding) {
        require(binding.expiresAt.isAfter(clock.instant()))
        require(binding.capabilityToken.length in 32..256)
        check(bindings.putIfAbsent(binding.caseId, binding) == null) { "FACTORY_BINDING_ALREADY_EXISTS" }
        store?.putBinding(toState(binding, leased = false))
    }

    fun context(
        caseId: UUID,
        namespaceId: UUID,
        agentName: String,
    ): Map<String, Any?> {
        val binding = validated(caseId, namespaceId, agentName, "lookup") ?: return emptyMap()
        if (binding.leased.get()) {
            logger.warn { "Factory result binding lookup: leased caseId=$caseId attemptId=${binding.attemptId}" }
            return emptyMap()
        }
        return mapOf("capabilityToken" to binding.capabilityToken, "attemptId" to binding.attemptId, "runtimeId" to binding.runtimeId)
    }

    fun acquire(
        caseId: UUID,
        namespaceId: UUID,
        agentName: String,
        allowExpired: Boolean = false,
    ): FactoryStepResultBinding? {
        val binding = validated(caseId, namespaceId, agentName, "acquire", allowExpired) ?: return null
        if (!binding.leased.compareAndSet(false, true)) {
            logger.warn { "Factory result binding acquire: already leased caseId=$caseId attemptId=${binding.attemptId}" }
            return null
        }
        store?.setLease(caseId, true)
        logger.info { "Factory result binding acquire: accepted caseId=$caseId attemptId=${binding.attemptId} agent=$agentName" }
        return binding
    }

    fun replaceLeased(
        current: FactoryStepResultBinding,
        replacement: FactoryStepResultBinding,
    ): Boolean {
        require(current.caseId == replacement.caseId)
        require(current.namespaceId == replacement.namespaceId)
        require(current.agentName == replacement.agentName)
        require(current.attemptId == replacement.attemptId)
        require(current.runtimeId == replacement.runtimeId)
        require(replacement.expiresAt.isAfter(clock.instant()))
        require(replacement.capabilityToken.length in 32..256)
        replacement.leased.set(true)
        val replaced = bindings.replace(current.caseId, current, replacement)
        if (replaced) store?.putBinding(toState(replacement, leased = true))
        return replaced
    }

    fun acknowledge(binding: FactoryStepResultBinding) {
        if (bindings.remove(binding.caseId, binding)) {
            store?.removeBinding(binding.caseId)
            logger.info { "Factory result binding acknowledge: removed caseId=${binding.caseId} attemptId=${binding.attemptId}" }
        }
        binding.leased.set(false)
    }

    fun release(binding: FactoryStepResultBinding) {
        if (bindings[binding.caseId] === binding) {
            binding.leased.set(false)
            store?.setLease(binding.caseId, false)
            logger.info { "Factory result binding release: available caseId=${binding.caseId} attemptId=${binding.attemptId}" }
        }
    }

    fun invalidate(binding: FactoryStepResultBinding) {
        if (bindings.remove(binding.caseId, binding)) {
            store?.removeBinding(binding.caseId)
            logger.warn { "Factory result binding invalidate: removed caseId=${binding.caseId} attemptId=${binding.attemptId}" }
        }
        binding.leased.set(false)
    }

    fun remove(caseId: UUID) {
        bindings.remove(caseId)
        store?.removeBinding(caseId)
    }

    internal fun contains(caseId: UUID) = bindings.containsKey(caseId)

    /** Test/observability accessor for the live binding of a case, or null when absent. */
    internal fun find(caseId: UUID): FactoryStepResultBinding? = bindings[caseId]

    private fun validated(
        caseId: UUID,
        namespaceId: UUID,
        agentName: String,
        operation: String,
        allowExpired: Boolean = false,
    ): FactoryStepResultBinding? {
        val binding = bindings[caseId]
        if (binding == null) {
            logger.warn { "Factory result binding $operation: absent caseId=$caseId agent=$agentName" }
            return null
        }
        if (!binding.expiresAt.isAfter(clock.instant()) && !allowExpired) {
            logger.warn { "Factory result binding $operation: expired caseId=$caseId attemptId=${binding.attemptId}" }
            return null
        }
        if (binding.namespaceId != namespaceId) {
            logger.warn { "Factory result binding $operation: namespace mismatch caseId=$caseId attemptId=${binding.attemptId}" }
            return null
        }
        if (binding.agentName != FACTORY_AGENT_WILDCARD && binding.agentName != agentName) {
            logger.warn {
                "Factory result binding $operation: agent mismatch caseId=$caseId attemptId=${binding.attemptId} expected=${binding.agentName} actual=$agentName"
            }
            return null
        }
        return binding
    }

    private fun toBinding(state: FactoryStepResultBindingState) =
        FactoryStepResultBinding(
            caseId = state.caseId,
            namespaceId = state.namespaceId,
            agentName = state.agentName,
            attemptId = state.attemptId,
            runtimeId = state.runtimeId,
            capabilityToken = state.capabilityToken,
            expiresAt = state.expiresAt,
            leased = AtomicBoolean(state.leased),
        )

    private fun toState(
        binding: FactoryStepResultBinding,
        leased: Boolean,
    ) = FactoryStepResultBindingState(
        caseId = binding.caseId,
        namespaceId = binding.namespaceId,
        agentName = binding.agentName,
        attemptId = binding.attemptId,
        runtimeId = binding.runtimeId,
        capabilityToken = binding.capabilityToken,
        expiresAt = binding.expiresAt,
        leased = leased,
    )
}
