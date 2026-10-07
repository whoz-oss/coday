package io.whozoss.factory.delivery.domain

import java.util.UUID

/**
 * Pure delivery-promotion policy: request validation, idempotency hashing and
 * the ordered, evidence-gated promotion rules between delivery stages.
 *
 * Faithful port of `factory/src/domain/delivery/delivery-policy.ts`.
 */

/** The stage every delivery starts in. */
const val DELIVERY_INITIAL_STAGE = "implementation-ready"

private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
private val REQUEST_FIELDS =
    setOf("deliveryId", "expectedRevision", "requestedStage", "evidenceIds", "idempotencyKey")

/** A validated promotion request, ready for policy evaluation. */
data class DeliveryPromotionRequest(
    val requestId: String,
    val deliveryId: String,
    val expectedRevision: Int,
    val requestedStage: String,
    val evidenceIds: List<String>,
    val idempotencyKey: String,
)

/** The controlling execution a promotion is bound to. */
data class DeliveryExecutionContext(
    val kind: String,
    val namespaceId: String,
    val workflowId: String,
    val caseId: String,
    val runtimeId: String,
    val actorId: String? = null,
)

/** The snapshot surface the promotion policy reads. */
data class DeliveryPromotionSnapshot(
    val revision: Int,
    val namespaceId: String,
    val workflowId: String,
    val parentCaseId: String,
    val definitionHash: String,
    val stage: String,
    val deliveryId: String,
    val environmentHash: String,
    val headCommit: String,
    val evidenceIds: List<String> = emptyList(),
)

/** One recorded evidence fact the promotion policy binds on. */
data class DeliveryEvidenceItem(
    val evidenceId: String,
    val namespaceId: String,
    val workflowId: String,
    val deliveryId: String,
    val environmentHash: String,
    val caseId: String,
    val headCommit: String,
    val kind: String,
    val outcome: String,
    val oracleId: String? = null,
    val sourceKind: String? = null,
)

/** A delivery definition together with its content hash. */
data class HashedDeliveryDefinition(
    val definition: DeliveryDefinition,
    val definitionHash: String,
)

/** Result of validating a raw promotion request. */
sealed interface DeliveryPromotionRequestValidation {
    data class Valid(val value: DeliveryPromotionRequest) : DeliveryPromotionRequestValidation
    data class Invalid(val code: String = DeliveryErrorCodes.INVALID_DELIVERY_REQUEST) :
        DeliveryPromotionRequestValidation
}

/** Inputs of a promotion policy evaluation. */
data class DeliveryPromotionEvaluation(
    val request: DeliveryPromotionRequest,
    val snapshot: DeliveryPromotionSnapshot?,
    val definition: HashedDeliveryDefinition,
    val evidence: List<DeliveryEvidenceItem>,
    val execution: DeliveryExecutionContext,
)

/** A promotion decision: allowed, or denied with a machine code and reason. */
sealed interface DeliveryPromotionDecision {
    data object Allowed : DeliveryPromotionDecision
    data class Denied(val code: String, val reason: String) : DeliveryPromotionDecision
}

/** Validates a raw promotion request against the expected delivery identity. */
fun validateDeliveryPromotionRequest(
    input: Map<String, Any?>?,
    expectedDeliveryId: String,
): DeliveryPromotionRequestValidation {
    if (input == null || input.keys.any { it !in REQUEST_FIELDS }) {
        return DeliveryPromotionRequestValidation.Invalid()
    }
    val deliveryId = input["deliveryId"] as? String
    val expectedRevision = (input["expectedRevision"] as? Number)?.toInt()
    val requestedStage = input["requestedStage"] as? String
    val evidenceIds = input["evidenceIds"] as? List<*>
    val idempotencyKey = input["idempotencyKey"] as? String
    if (deliveryId != expectedDeliveryId ||
        !SAFE_ID.matches(deliveryId) ||
        !isSafeInteger(input["expectedRevision"]) ||
        (expectedRevision ?: 0) < 1 ||
        requestedStage !in DeliveryDefinitionSchema.STAGES
    ) {
        return DeliveryPromotionRequestValidation.Invalid()
    }
    if (evidenceIds == null ||
        evidenceIds.size > 100 ||
        evidenceIds.map { it.toString() }.toSet().size != evidenceIds.size ||
        evidenceIds.any { !SAFE_ID.matches((it as? String) ?: "") }
    ) {
        return DeliveryPromotionRequestValidation.Invalid()
    }
    if (idempotencyKey == null ||
        idempotencyKey.isEmpty() ||
        idempotencyKey.length > 128 ||
        idempotencyKey.contains('\r') ||
        idempotencyKey.contains('\n')
    ) {
        return DeliveryPromotionRequestValidation.Invalid()
    }
    return DeliveryPromotionRequestValidation.Valid(
        DeliveryPromotionRequest(
            requestId = UUID.randomUUID().toString(),
            deliveryId = deliveryId,
            expectedRevision = expectedRevision!!,
            requestedStage = requestedStage!!,
            evidenceIds = evidenceIds.map { it as String },
            idempotencyKey = idempotencyKey,
        ),
    )
}

private fun isSafeInteger(value: Any?): Boolean = when (value) {
    is Int -> true
    is Long -> value >= Int.MIN_VALUE && value <= Int.MAX_VALUE
    else -> false
}

/** Idempotency scope hash: binds the request to its controlling execution. */
fun deliveryScopeHash(
    namespaceId: String,
    request: DeliveryPromotionRequest,
    execution: DeliveryExecutionContext,
): String = CanonicalHash.sha256(
    mapOf(
        "namespaceId" to namespaceId,
        "deliveryId" to request.deliveryId,
        "caseId" to execution.caseId,
        "runtimeId" to execution.runtimeId,
        "idempotencyKey" to request.idempotencyKey,
    ),
)

/** Idempotency semantic hash: the exact promotion content being requested. */
fun deliverySemanticHash(request: DeliveryPromotionRequest): String = CanonicalHash.sha256(
    mapOf(
        "deliveryId" to request.deliveryId,
        "expectedRevision" to request.expectedRevision,
        "requestedStage" to request.requestedStage,
        "evidenceIds" to request.evidenceIds.sorted(),
    ),
)

/** Evaluates whether a promotion request may proceed against the current snapshot. */
fun evaluateDeliveryPromotion(evaluation: DeliveryPromotionEvaluation): DeliveryPromotionDecision {
    val request = evaluation.request
    val snapshot = evaluation.snapshot
    val definition = evaluation.definition
    val evidence = evaluation.evidence
    val execution = evaluation.execution
    if (snapshot == null) return denied("DELIVERY_NOT_FOUND", "delivery_not_found")
    if (snapshot.revision != request.expectedRevision) return denied("REVISION_CONFLICT", "stale_delivery_revision")
    if (snapshot.namespaceId != execution.namespaceId ||
        snapshot.workflowId != execution.workflowId ||
        snapshot.parentCaseId != execution.caseId
    ) {
        return denied("DELIVERY_SCOPE_MISMATCH", "controlling_execution_mismatch")
    }
    if (snapshot.definitionHash != definition.definitionHash) {
        return denied("DELIVERY_DEFINITION_MISMATCH", "definition_identity_mismatch")
    }
    val currentIndex = DeliveryDefinitionSchema.STAGES.indexOf(snapshot.stage)
    val requestedIndex = DeliveryDefinitionSchema.STAGES.indexOf(request.requestedStage)
    if (requestedIndex != currentIndex + 1) return denied("ILLEGAL_PROMOTION", "ordered_promotion_required")
    val checkpoint = definition.definition.checkpoints.find { it.stage == request.requestedStage }
        ?: return denied("DELIVERY_DEFINITION_MISMATCH", "checkpoint_missing")
    val isHuman = checkpoint.responsibility.kind == "human"
    val authorized = if (isHuman) {
        execution.kind == "factory-human" && execution.actorId != null && execution.runtimeId == "factory-dashboard"
    } else {
        execution.kind == "factory-control-plane" && execution.runtimeId == "factory-dashboard"
    }
    if (!authorized) return denied("ACTOR_NOT_AUTHORIZED", "factory_responsibility_required")
    val selected = ArrayList<DeliveryEvidenceItem>()
    for (id in request.evidenceIds) {
        val item = evidence.find { it.evidenceId == id }
            ?: return denied("EVIDENCE_NOT_FOUND", "evidence_not_found")
        if (item.namespaceId != snapshot.namespaceId ||
            item.workflowId != snapshot.workflowId ||
            item.deliveryId != snapshot.deliveryId ||
            item.environmentHash != snapshot.environmentHash ||
            item.caseId != snapshot.parentCaseId ||
            item.headCommit != snapshot.headCommit
        ) {
            return denied("EVIDENCE_SCOPE_MISMATCH", "bounded_fact_mismatch")
        }
        selected.add(item)
    }
    for (requirement in checkpoint.requiredEvidence) {
        val match = selected.find {
            it.kind == requirement.kind &&
                it.outcome == requirement.outcome &&
                (requirement.oracleId == null || it.oracleId == requirement.oracleId) &&
                it.sourceKind != "agent"
        }
        if (match == null) return denied("PASS_EVIDENCE_REQUIRED", "${requirement.kind}:${requirement.outcome}")
    }
    if (request.requestedStage == "release-approved" &&
        selected.none { it.kind == "human-decision" && it.outcome == "approved" && it.sourceKind == "factory-human" }
    ) {
        return denied("HUMAN_APPROVAL_REQUIRED", "release_approval_missing")
    }
    if (request.requestedStage == "deployed" &&
        currentIndex < DeliveryDefinitionSchema.STAGES.indexOf("release-approved")
    ) {
        return denied("RELEASE_NOT_APPROVED", "release_approval_missing")
    }
    if (request.requestedStage == "production-verified" &&
        selected.none { it.kind == "smoke-result" && it.outcome == "pass" }
    ) {
        return denied("SMOKE_PASS_REQUIRED", "production_smoke_missing")
    }
    return DeliveryPromotionDecision.Allowed
}

private fun denied(code: String, reason: String): DeliveryPromotionDecision =
    DeliveryPromotionDecision.Denied(code, reason)

/** Applies an allowed promotion to a snapshot, producing the next revision. */
fun applyDeliveryPromotion(
    snapshot: Map<String, Any?>,
    request: DeliveryPromotionRequest,
    observedAt: String = nowIso(),
): Map<String, Any?> {
    val evidenceIds = LinkedHashSet<String>()
    (snapshot["evidenceIds"] as? List<*>)?.forEach { evidenceIds.add(it.toString()) }
    evidenceIds.addAll(request.evidenceIds)
    val revision = (snapshot["revision"] as? Number)?.toInt() ?: 0
    return snapshot + mapOf(
        "stage" to request.requestedStage,
        "revision" to revision + 1,
        "updatedAt" to observedAt,
        "evidenceIds" to evidenceIds.toList(),
    )
}

/** ISO-8601 UTC timestamp with exactly three fractional digits, like `new Date().toISOString()`. */
fun nowIso(): String = ISO_MILLIS.format(java.time.Instant.now())

private val ISO_MILLIS: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(java.time.ZoneOffset.UTC)
