package io.whozoss.factory.delivery.port

import io.whozoss.factory.delivery.domain.CanonicalHash
import io.whozoss.factory.delivery.domain.DeliveryErrorCodes
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Trusted delivery-target registry.
 *
 * Port of `factory/src/adapters/delivery/delivery-target-registry.ts`: targets
 * come only from trusted configuration, are deduplicated by id and bound to a
 * canonical `targetHash` used by operations and policies to detect target drift.
 */

/** A validated, frozen delivery target with its canonical hash. */
data class DeliveryTarget(
    val targetId: String,
    val environmentKind: String,
    val adapterId: String,
    val adapterTargetRef: String,
    val supportsRollback: Boolean,
    val verificationSuiteId: String? = null,
    val verificationSuiteHash: String? = null,
    val targetHash: String,
)

/** Result of a target lookup. */
sealed interface DeliveryTargetLookup {
    data class Ok(val target: DeliveryTarget) : DeliveryTargetLookup
    data class NotFound(val code: String) : DeliveryTargetLookup
}

/** The lookup surface a target registry exposes. */
interface DeliveryTargetRegistry {
    fun lookup(targetId: String?): DeliveryTargetLookup

    /** Registers (or replaces) a trusted target from a raw definition. */
    fun register(definition: Map<String, Any?>)

    /** Test/bootstrap helper: forget every registered target. */
    fun clear()
}

private val TARGET_SAFE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
private val TARGET_DIGEST = Regex("^sha256:[0-9a-f]{64}$", RegexOption.IGNORE_CASE)
private val TARGET_FIELDS = listOf(
    "targetId", "environmentKind", "adapterId", "adapterTargetRef",
    "supportsRollback", "verificationSuiteId", "verificationSuiteHash",
)
private val ENVIRONMENT_KINDS = setOf("development", "staging", "production")

/**
 * In-memory [DeliveryTargetRegistry], configurable from trusted definitions.
 *
 * A registry with no registered target reports `DELIVERY_TARGET_REGISTRY_UNAVAILABLE`
 * for every lookup, exactly like the Node `unavailableDeliveryTargetRegistry`.
 */
@Component
class ConfigurableDeliveryTargetRegistry : DeliveryTargetRegistry {

    private val targets = ConcurrentHashMap<String, DeliveryTarget>()

    override fun lookup(targetId: String?): DeliveryTargetLookup {
        if (targets.isEmpty()) {
            return DeliveryTargetLookup.NotFound(DeliveryErrorCodes.DELIVERY_TARGET_REGISTRY_UNAVAILABLE)
        }
        if (targetId == null || !TARGET_SAFE.matches(targetId)) {
            return DeliveryTargetLookup.NotFound(DeliveryErrorCodes.DELIVERY_TARGET_NOT_FOUND)
        }
        val target = targets[targetId] ?: return DeliveryTargetLookup.NotFound(DeliveryErrorCodes.DELIVERY_TARGET_NOT_FOUND)
        return DeliveryTargetLookup.Ok(target)
    }

    override fun register(definition: Map<String, Any?>) {
        if (definition.keys.any { it !in TARGET_FIELDS } ||
            !TARGET_SAFE.matches((definition["targetId"] as? String) ?: "") ||
            definition["environmentKind"] !in ENVIRONMENT_KINDS ||
            !TARGET_SAFE.matches((definition["adapterId"] as? String) ?: "") ||
            !TARGET_SAFE.matches((definition["adapterTargetRef"] as? String) ?: "") ||
            definition["supportsRollback"] !is Boolean
        ) {
            throw IllegalArgumentException("INVALID_DELIVERY_TARGET")
        }
        val verificationSuiteId = definition["verificationSuiteId"] as? String
        if (verificationSuiteId != null && !TARGET_SAFE.matches(verificationSuiteId)) {
            throw IllegalArgumentException("INVALID_DELIVERY_TARGET")
        }
        val verificationSuiteHash = definition["verificationSuiteHash"] as? String
        if (verificationSuiteHash != null && !TARGET_DIGEST.matches(verificationSuiteHash)) {
            throw IllegalArgumentException("INVALID_DELIVERY_TARGET")
        }
        val targetHash = CanonicalHash.canonicalDeliveryHash(definition)
        val target = DeliveryTarget(
            targetId = definition["targetId"] as String,
            environmentKind = definition["environmentKind"] as String,
            adapterId = definition["adapterId"] as String,
            adapterTargetRef = definition["adapterTargetRef"] as String,
            supportsRollback = definition["supportsRollback"] as Boolean,
            verificationSuiteId = verificationSuiteId,
            verificationSuiteHash = verificationSuiteHash,
            targetHash = targetHash,
        )
        targets[target.targetId] = target
    }

    override fun clear() {
        targets.clear()
    }
}
