package io.whozoss.factory.delivery.port

import io.whozoss.factory.delivery.domain.DeliveryErrorCodes
import io.whozoss.factory.delivery.domain.DeliveryEvidenceItem
import io.whozoss.factory.delivery.domain.CanonicalHash
import io.whozoss.factory.persistence.TenantScope
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Evidence recording and retrieval for the delivery aggregate.
 *
 * Port of `factory/src/adapters/persistence/delivery-evidence-store.ts`. Evidence
 * facts are immutable and idempotent: recording is bound by a scope hash
 * (controlling execution plus idempotency key) and a semantic hash (the exact
 * fact content), so a replay is a no-op and a key reuse with a different fact is
 * an `IDEMPOTENCY_KEY_COLLISION`.
 */
interface DeliveryEvidenceStore {

    /** Every recorded evidence fact of a delivery, in insertion order. */
    fun list(scope: TenantScope, namespaceId: String, deliveryId: String): List<DeliveryEvidenceItem>

    /** Records (or idempotently replays) one evidence fact. */
    fun record(
        scope: TenantScope,
        namespaceId: String,
        input: Map<String, Any?>,
        source: Map<String, Any?>,
    ): DeliveryEvidenceRecordResult

    /** Test/bootstrap helper: forget every recorded fact. */
    fun clear()
}

/** Result of recording an evidence fact. */
data class DeliveryEvidenceRecordResult(
    val ok: Boolean,
    val created: Boolean = false,
    val evidence: Map<String, Any?>? = null,
    val errorCode: String? = null,
)

private val EVIDENCE_SAFE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
private val EVIDENCE_UUID =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
private val EVIDENCE_HASH = Regex("^sha256:[0-9a-f]{64}$")
private val EVIDENCE_SHA = Regex("^(?:[0-9a-f]{40}|[0-9a-f]{64})$", RegexOption.IGNORE_CASE)
private val EVIDENCE_FIELDS = setOf(
    "deliveryId", "workflowId", "environmentHash", "caseId", "runtimeId", "headCommit",
    "kind", "outcome", "oracleId", "facts", "idempotencyKey",
)

/** Validates a raw delivery evidence fact. */
@Suppress("UNCHECKED_CAST")
internal fun validateDeliveryEvidence(input: Map<String, Any?>?): Map<String, Any?>? {
    if (input == null || input.keys.any { it !in EVIDENCE_FIELDS }) return null
    val stringFields = listOf("deliveryId", "workflowId", "runtimeId", "kind", "outcome", "idempotencyKey")
    if (stringFields.any { !EVIDENCE_SAFE.matches((input[it] as? String) ?: "") }) return null
    if (!EVIDENCE_HASH.matches((input["environmentHash"] as? String) ?: "")) return null
    if (!EVIDENCE_UUID.matches((input["caseId"] as? String) ?: "")) return null
    if (!EVIDENCE_SHA.matches((input["headCommit"] as? String) ?: "")) return null
    val facts = input["facts"] as? Map<*, *> ?: return null
    if (facts.size > 32) return null
    return CanonicalHash.canonical(input) as? Map<String, Any?>
}

/**
 * In-memory [DeliveryEvidenceStore].
 *
 * Deliberately process-local: delivery evidence is a fast, idempotent fact log
 * that the promotion policy reads; the durable promotion/operation ledger lives
 * in `deliveries` / `delivery_journal`. Thread-safe and shared by the single
 * application context.
 */
@Component
class InMemoryDeliveryEvidenceStore : DeliveryEvidenceStore {

    private val byDelivery = ConcurrentHashMap<String, MutableList<Map<String, Any?>>>()

    override fun list(scope: TenantScope, namespaceId: String, deliveryId: String): List<DeliveryEvidenceItem> =
        synchronized(this) {
            byDelivery[key(scope, namespaceId, deliveryId)].orEmpty().map { it.toEvidenceItem() }
        }

    override fun record(
        scope: TenantScope,
        namespaceId: String,
        input: Map<String, Any?>,
        source: Map<String, Any?>,
    ): DeliveryEvidenceRecordResult {
        val value = validateDeliveryEvidence(input)
            ?: return DeliveryEvidenceRecordResult(ok = false, errorCode = DeliveryErrorCodes.INVALID_DELIVERY_EVIDENCE)
        val deliveryId = value["deliveryId"] as String
        val scopeHash = CanonicalHash.sha256(
            mapOf(
                "namespaceId" to namespaceId,
                "deliveryId" to value["deliveryId"],
                "workflowId" to value["workflowId"],
                "caseId" to value["caseId"],
                "runtimeId" to value["runtimeId"],
                "idempotencyKey" to value["idempotencyKey"],
            ),
        )
        val semanticHash = CanonicalHash.sha256(value.filterKeys { it != "idempotencyKey" })
        synchronized(this) {
            val existing = byDelivery.getOrPut(key(scope, namespaceId, deliveryId)) { mutableListOf() }
            val prior = existing.find { (it["idempotency"] as? Map<*, *>)?.get("scopeHash") == scopeHash }
            if (prior != null) {
                val priorSemantic = (prior["idempotency"] as? Map<*, *>)?.get("semanticHash")
                return if (priorSemantic == semanticHash) {
                    DeliveryEvidenceRecordResult(ok = true, created = false, evidence = prior)
                } else {
                    DeliveryEvidenceRecordResult(
                        ok = false,
                        errorCode = DeliveryErrorCodes.IDEMPOTENCY_KEY_COLLISION,
                    )
                }
            }
            val evidence = linkedMapOf<String, Any?>(
                "evidenceId" to UUID.randomUUID().toString(),
                "namespaceId" to namespaceId,
            )
            evidence.putAll(value)
            evidence["source"] = CanonicalHash.canonical(source)
            evidence["observedAt"] = io.whozoss.factory.delivery.domain.nowIso()
            evidence["idempotency"] = mapOf("scopeHash" to scopeHash, "semanticHash" to semanticHash)
            existing.add(evidence)
            return DeliveryEvidenceRecordResult(ok = true, created = true, evidence = evidence)
        }
    }

    override fun clear() {
        synchronized(this) { byDelivery.clear() }
    }

    private fun key(scope: TenantScope, namespaceId: String, deliveryId: String): String =
        "${scope.organizationId}\u0000${scope.workstreamId}\u0000$namespaceId\u0000$deliveryId"

    private fun Map<String, Any?>.toEvidenceItem(): DeliveryEvidenceItem = DeliveryEvidenceItem(
        evidenceId = this["evidenceId"] as String,
        namespaceId = this["namespaceId"] as String,
        workflowId = this["workflowId"] as String,
        deliveryId = this["deliveryId"] as String,
        environmentHash = this["environmentHash"] as String,
        caseId = this["caseId"] as String,
        headCommit = this["headCommit"] as String,
        kind = this["kind"] as String,
        outcome = this["outcome"] as String,
        oracleId = this["oracleId"] as? String,
        sourceKind = (this["source"] as? Map<*, *>)?.get("kind") as? String,
    )
}
