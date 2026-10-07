package io.whozoss.factory.delivery.persistence

import io.whozoss.factory.delivery.domain.CanonicalHash
import io.whozoss.factory.delivery.domain.DeliveryErrorCodes
import io.whozoss.factory.delivery.domain.deliveryException

/**
 * Snapshot shape validation and canonical hashing shared by the SQL delivery
 * adapter.
 *
 * Port of the `validSnapshot` / `hash()` helpers of
 * `factory/src/adapters/persistence/sql/sql-delivery-repository.ts`. The
 * delivery payload is kept verbatim as a JSON object; only the identity and
 * optimistic-locking fields are validated here.
 */
object DeliverySnapshots {

    private val UUID = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
    )
    private val SAFE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private val SHA = Regex("^(?:[0-9a-f]{40}|[0-9a-f]{64})$", RegexOption.IGNORE_CASE)
    private val HASH = Regex("^sha256:[0-9a-f]{64}$")

    /** Whether [value] is a structurally valid delivery snapshot. */
    fun valid(value: Map<String, Any?>?): Boolean {
        if (value == null) return false
        if (value["schemaVersion"] != "1") return false
        if (!UUID.matches(value["namespaceId"] as? String ?: "")) return false
        if (!SAFE.matches(value["deliveryId"] as? String ?: "")) return false
        if (!SAFE.matches(value["workflowId"] as? String ?: "")) return false
        if (!SAFE.matches(value["environmentId"] as? String ?: "")) return false
        if (!HASH.matches(value["environmentHash"] as? String ?: "")) return false
        if (!UUID.matches(value["parentCaseId"] as? String ?: "")) return false
        if (!SAFE.matches(value["runtimeId"] as? String ?: "")) return false
        if (!SHA.matches(value["baseCommit"] as? String ?: "")) return false
        if (!SHA.matches(value["headCommit"] as? String ?: "")) return false
        val revision = (value["revision"] as? Number)?.toInt() ?: return false
        return revision > 0
    }

    /** Canonical hash of the snapshot without its own `snapshotHash` field. */
    fun payloadHash(snapshot: Map<String, Any?>): String =
        CanonicalHash.sha256(snapshot.filterKeys { it != "snapshotHash" })

    /** Fail-closed scope validation before any statement is issued. */
    fun assertScope(namespaceId: String?, deliveryId: String?) {
        if (namespaceId == null || !UUID.matches(namespaceId) || deliveryId == null || !SAFE.matches(deliveryId)) {
            throw deliveryException(DeliveryErrorCodes.INVALID_DELIVERY_SCOPE, "Invalid delivery scope")
        }
    }
}
