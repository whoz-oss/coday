package io.whozoss.factory.delivery.domain

/**
 * Pure delivery-operation domain: operation kinds and states, request
 * normalization, identity derivation, state-machine transitions and the
 * persisted operation-record contract.
 *
 * Faithful port of `factory/src/domain/delivery/delivery-operation-definition.ts`.
 */
object DeliveryOperationSchema {
    /** Delivery operation kinds, in vocabulary order. */
    val KINDS: List<String> = listOf("deployment", "production-verification", "rollback", "rollback-verification")

    /** Delivery operation lifecycle states. */
    val STATES: List<String> = listOf("pending", "running", "succeeded", "failed", "indeterminate")
}

private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
private val SHA = Regex("^[0-9a-f]{40}$", RegexOption.IGNORE_CASE)
private val DIGEST = Regex("^sha256:[0-9a-f]{64}$", RegexOption.IGNORE_CASE)
private val MEDIA = Regex(
    "^[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}$",
    RegexOption.IGNORE_CASE,
)
private val BASE_FIELDS = listOf("kind", "expectedRevision", "idempotencyKey", "targetId")
private val SPEC_FIELDS = mapOf(
    "deployment" to listOf("artifactRef", "releaseRef"),
    "production-verification" to listOf("deploymentRef"),
    "rollback" to listOf("deploymentRef", "priorArtifactRef", "priorReleaseRef", "rollbackRequestId", "approvedEvidenceId"),
    "rollback-verification" to listOf("rollbackRef"),
)
private val ALLOWED_TRANSITIONS = mapOf(
    "pending" to listOf("running", "failed"),
    "running" to listOf("succeeded", "failed", "indeterminate"),
    "indeterminate" to listOf("succeeded", "failed"),
    "succeeded" to emptyList<String>(),
    "failed" to emptyList<String>(),
)
private val RECORD_FIELDS = listOf(
    "recordType", "operationId", "kind", "expectedRevision", "targetRef", "artifactRef", "releaseRef",
    "deploymentRef", "rollbackRef", "state", "attempt", "requestedAt", "startedAt", "completedAt", "execution",
    "adapterCorrelation", "scopeHash", "semanticHash", "result", "error", "resolvedOperationId", "sourceCommit",
    "artifactDigest", "rollbackRequestId", "approvedEvidenceId",
)
private val ISO_MILLIS = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")

/** Stable `sha256:`-prefixed hash of a value's canonical JSON form. */
fun canonicalDeliveryHash(value: Any?): String = CanonicalHash.canonicalDeliveryHash(value)

/** A normalized, frozen delivery-operation request (refs kept as JSON maps). */
data class NormalizedDeliveryOperationRequest(
    val kind: String,
    val expectedRevision: Int,
    val idempotencyKey: String,
    val targetId: String,
    val artifactRef: Map<String, Any?>? = null,
    val releaseRef: Map<String, Any?>? = null,
    val deploymentRef: Map<String, Any?>? = null,
    val rollbackRef: Map<String, Any?>? = null,
    val priorArtifactRef: Map<String, Any?>? = null,
    val priorReleaseRef: Map<String, Any?>? = null,
    val rollbackRequestId: String? = null,
    val approvedEvidenceId: String? = null,
)

/** Result of normalizing a raw delivery-operation request. */
sealed interface DeliveryOperationRequestNormalization {
    data class Valid(val value: NormalizedDeliveryOperationRequest) : DeliveryOperationRequestNormalization
    data class Invalid(
        val path: String,
        val reason: String = "invalid_value",
        val code: String = DeliveryErrorCodes.INVALID_DELIVERY_OPERATION_REQUEST,
    ) : DeliveryOperationRequestNormalization
}

private sealed interface RefResult<out T> {
    data class Ok<T>(val value: T) : RefResult<T>
    data class Err(val path: String, val reason: String = "invalid_value") : RefResult<Nothing>
}

/** Validates and normalizes a raw delivery-operation request. */
fun normalizeDeliveryOperationRequest(input: Map<String, Any?>?): DeliveryOperationRequestNormalization {
    val kind = input?.get("kind") as? String
    if (input == null || kind !in DeliveryOperationSchema.KINDS) {
        return DeliveryOperationRequestNormalization.Invalid("$", "unknown_or_missing_field")
    }
    val expectedFields = BASE_FIELDS + (SPEC_FIELDS[kind] ?: emptyList())
    if (input.keys.any { it !in expectedFields }) {
        return DeliveryOperationRequestNormalization.Invalid("$", "unknown_or_missing_field")
    }
    val expectedRevision = (input["expectedRevision"] as? Number)?.toInt()
    if (!isSafeInt(input["expectedRevision"]) ||
        (expectedRevision ?: 0) < 1 ||
        !id(input["idempotencyKey"]) ||
        !id(input["targetId"])
    ) {
        return DeliveryOperationRequestNormalization.Invalid("$")
    }
    val out = NormalizedDeliveryOperationRequest(
        kind = kind!!,
        expectedRevision = expectedRevision!!,
        idempotencyKey = input["idempotencyKey"] as String,
        targetId = input["targetId"] as String,
    )
    if (kind == "deployment") {
        val a = artifactRef(input["artifactRef"])
        if (a is RefResult.Err) return DeliveryOperationRequestNormalization.Invalid(a.path, a.reason)
        val r = releaseRef(input["releaseRef"])
        if (r is RefResult.Err) return DeliveryOperationRequestNormalization.Invalid(r.path, r.reason)
        val artifact = (a as RefResult.Ok).value
        val release = (r as RefResult.Ok).value
        if (artifact["digest"] != release["artifactDigest"] || artifact["sourceCommit"] != release["sourceCommit"]) {
            return DeliveryOperationRequestNormalization.Invalid("releaseRef", "artifact_identity_mismatch")
        }
        return DeliveryOperationRequestNormalization.Valid(
            out.copy(artifactRef = artifact, releaseRef = release),
        )
    }
    if (kind == "production-verification") {
        val d = operationRef(input["deploymentRef"], "deploymentRef", "deployment")
        if (d is RefResult.Err) return DeliveryOperationRequestNormalization.Invalid(d.path, d.reason)
        return DeliveryOperationRequestNormalization.Valid(
            out.copy(deploymentRef = (d as RefResult.Ok).value),
        )
    }
    if (kind == "rollback") {
        val d = operationRef(input["deploymentRef"], "deploymentRef", "deployment")
        if (d is RefResult.Err) return DeliveryOperationRequestNormalization.Invalid(d.path, d.reason)
        val a = artifactRef(input["priorArtifactRef"], "priorArtifactRef")
        if (a is RefResult.Err) return DeliveryOperationRequestNormalization.Invalid(a.path, a.reason)
        val r = releaseRef(input["priorReleaseRef"], "priorReleaseRef")
        if (r is RefResult.Err) return DeliveryOperationRequestNormalization.Invalid(r.path, r.reason)
        val artifact = (a as RefResult.Ok).value
        val release = (r as RefResult.Ok).value
        if (!id(input["rollbackRequestId"]) ||
            !id(input["approvedEvidenceId"]) ||
            artifact["digest"] != release["artifactDigest"] ||
            artifact["sourceCommit"] != release["sourceCommit"]
        ) {
            return DeliveryOperationRequestNormalization.Invalid("$", "rollback_identity_mismatch")
        }
        return DeliveryOperationRequestNormalization.Valid(
            out.copy(
                deploymentRef = (d as RefResult.Ok).value,
                priorArtifactRef = artifact,
                priorReleaseRef = release,
                rollbackRequestId = input["rollbackRequestId"] as String,
                approvedEvidenceId = input["approvedEvidenceId"] as String,
            ),
        )
    }
    // rollback-verification
    val r = operationRef(input["rollbackRef"], "rollbackRef", "rollback")
    if (r is RefResult.Err) return DeliveryOperationRequestNormalization.Invalid(r.path, r.reason)
    return DeliveryOperationRequestNormalization.Valid(
        out.copy(rollbackRef = (r as RefResult.Ok).value),
    )
}

/** The controlling scope an operation identity is derived from. */
data class DeliveryOperationScope(
    val namespaceId: String,
    val workflowId: String,
    val deliveryId: String,
    val caseId: String,
    val runtimeId: String,
)

/** The derived identity of a delivery operation. */
data class DeliveryOperationIdentity(
    val operationId: String,
    val scopeHash: String,
    val semanticHash: String,
)

/** Result of deriving a delivery-operation identity. */
sealed interface DeliveryOperationIdentityDerivation {
    data class Valid(val value: DeliveryOperationIdentity) : DeliveryOperationIdentityDerivation
    data class Invalid(
        val path: String,
        val reason: String = "invalid_value",
        val code: String = DeliveryErrorCodes.INVALID_DELIVERY_OPERATION_REQUEST,
    ) : DeliveryOperationIdentityDerivation
}

/** Derives the deterministic idempotency identity of an operation request. */
fun deriveDeliveryOperationIdentity(
    scope: DeliveryOperationScope,
    request: NormalizedDeliveryOperationRequest,
    targetHash: String?,
): DeliveryOperationIdentityDerivation {
    val ids = listOf(scope.namespaceId, scope.workflowId, scope.deliveryId, scope.caseId, scope.runtimeId)
    if (ids.any { !id(it) }) return DeliveryOperationIdentityDerivation.Invalid("scope")
    if (!digest(targetHash)) return DeliveryOperationIdentityDerivation.Invalid("targetHash")
    val scopeHash = canonicalDeliveryHash(
        mapOf(
            "namespaceId" to scope.namespaceId,
            "workflowId" to scope.workflowId,
            "deliveryId" to scope.deliveryId,
            "caseId" to scope.caseId,
            "runtimeId" to scope.runtimeId,
            "idempotencyKey" to request.idempotencyKey,
        ),
    )
    val semantic = LinkedHashMap<String, Any?>()
    semantic["kind"] = request.kind
    semantic["expectedRevision"] = request.expectedRevision
    semantic["targetHash"] = targetHash
    request.artifactRef?.let { semantic["artifactRef"] = it }
    request.releaseRef?.let { semantic["releaseRef"] = it }
    request.deploymentRef?.let { semantic["deploymentRef"] = it }
    request.rollbackRef?.let { semantic["rollbackRef"] = it }
    request.priorArtifactRef?.let { semantic["priorArtifactRef"] = it }
    request.priorReleaseRef?.let { semantic["priorReleaseRef"] = it }
    request.rollbackRequestId?.let { semantic["rollbackRequestId"] = it }
    request.approvedEvidenceId?.let { semantic["approvedEvidenceId"] = it }
    val semanticHash = canonicalDeliveryHash(semantic)
    return DeliveryOperationIdentityDerivation.Valid(
        DeliveryOperationIdentity(
            operationId = "dop_${scopeHash.drop(7).take(32)}",
            scopeHash = scopeHash,
            semanticHash = semanticHash,
        ),
    )
}

/** An adapter observation used to reconcile an indeterminate operation. */
data class DeliveryOperationObservation(
    val operationId: String? = null,
    val state: String? = null,
    val result: Any? = null,
    val error: Any? = null,
    val adapterCorrelation: Any? = null,
)

/** Result of validating an operation state transition. */
sealed interface DeliveryOperationTransitionValidation {
    data object Valid : DeliveryOperationTransitionValidation
    data class Invalid(val code: String) : DeliveryOperationTransitionValidation
}

/** Validates a persisted operation state transition against the state machine. */
fun validateDeliveryOperationTransition(
    previous: Map<String, Any?>?,
    next: Map<String, Any?>?,
    inspectedObservation: DeliveryOperationObservation? = null,
): DeliveryOperationTransitionValidation {
    if (previous == null || next == null ||
        previous["operationId"] != next["operationId"] ||
        previous["state"] !in DeliveryOperationSchema.STATES ||
        next["state"] !in (ALLOWED_TRANSITIONS[previous["state"] as String] ?: emptyList())
    ) {
        return DeliveryOperationTransitionValidation.Invalid(DeliveryErrorCodes.INVALID_DELIVERY_OPERATION_TRANSITION)
    }
    if (previous["state"] == "indeterminate") {
        val valid = inspectedObservation != null &&
            inspectedObservation.operationId == previous["operationId"] &&
            inspectedObservation.state == next["state"] &&
            next["state"] in listOf("succeeded", "failed") &&
            next["resolvedOperationId"] == previous["operationId"]
        if (!valid) {
            return DeliveryOperationTransitionValidation.Invalid(
                DeliveryErrorCodes.DELIVERY_OPERATION_RECONCILIATION_REQUIRED,
            )
        }
    }
    return DeliveryOperationTransitionValidation.Valid
}

/** Result of validating a persisted operation record. */
sealed interface DeliveryOperationRecordValidation {
    data class Valid(val value: Map<String, Any?>) : DeliveryOperationRecordValidation
    data class Invalid(val code: String, val path: String? = null) : DeliveryOperationRecordValidation
}

/** Validates a persisted delivery-operation record against the contract. */
fun validateDeliveryOperationRecord(value: Map<String, Any?>?): DeliveryOperationRecordValidation {
    if (value == null ||
        value.keys.any { it !in RECORD_FIELDS } ||
        value["recordType"] != "delivery-operation" ||
        !id(value["operationId"]) ||
        value["kind"] !in DeliveryOperationSchema.KINDS ||
        value["state"] !in DeliveryOperationSchema.STATES ||
        !isSafeInt(value["expectedRevision"]) ||
        !isSafeInt(value["attempt"]) ||
        ((value["attempt"] as Number).toInt()) < 0 ||
        !digest(value["scopeHash"]) ||
        !digest(value["semanticHash"])
    ) {
        return DeliveryOperationRecordValidation.Invalid(DeliveryErrorCodes.INVALID_DELIVERY_OPERATION_RECORD)
    }
    for (key in listOf("requestedAt", "startedAt", "completedAt")) {
        val raw = value[key] ?: continue
        if (raw !is String || !ISO_MILLIS.matches(raw) || !isCanonicalIso(raw)) {
            return DeliveryOperationRecordValidation.Invalid(
                DeliveryErrorCodes.INVALID_DELIVERY_OPERATION_RECORD,
                key,
            )
        }
    }
    return DeliveryOperationRecordValidation.Valid(value)
}

private fun isCanonicalIso(raw: String): Boolean = try {
    val instant = java.time.Instant.parse(raw)
    nowIsoOf(instant) == raw
} catch (_: Exception) {
    false
}

private fun nowIsoOf(instant: java.time.Instant): String =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(java.time.ZoneOffset.UTC)
        .format(instant)

private fun id(value: Any?): Boolean = value is String && SAFE_ID.matches(value)
private fun digest(value: Any?): Boolean = value is String && DIGEST.matches(value)
private fun sha(value: Any?): Boolean = value is String && SHA.matches(value)
private fun isSafeInt(value: Any?): Boolean = when (value) {
    is Int -> true
    is Long -> value >= Int.MIN_VALUE && value <= Int.MAX_VALUE
    else -> false
}

private fun exactKeys(value: Map<*, *>, fields: List<String>): Boolean =
    value.keys.all { it in fields } && fields.all { value.containsKey(it) }

private fun artifactRef(value: Any?, path: String = "artifactRef"): RefResult<Map<String, Any?>> {
    val v = value as? Map<*, *> ?: return RefResult.Err(path)
    if (!exactKeys(v, listOf("digest", "mediaType", "producerRef", "buildRef", "sourceCommit")) ||
        !digest(v["digest"]) ||
        !MEDIA.matches((v["mediaType"] as? String) ?: "") ||
        !id(v["producerRef"]) ||
        !id(v["buildRef"]) ||
        !sha(v["sourceCommit"])
    ) {
        return RefResult.Err(path)
    }
    return RefResult.Ok(
        mapOf(
            "digest" to (v["digest"] as String).lowercase(),
            "mediaType" to v["mediaType"],
            "producerRef" to v["producerRef"],
            "buildRef" to v["buildRef"],
            "sourceCommit" to (v["sourceCommit"] as String).lowercase(),
        ),
    )
}

private fun releaseRef(value: Any?, path: String = "releaseRef"): RefResult<Map<String, Any?>> {
    val v = value as? Map<*, *> ?: return RefResult.Err(path)
    if (!exactKeys(v, listOf("releaseId", "artifactDigest", "sourceCommit", "approvedEvidenceId")) ||
        !id(v["releaseId"]) ||
        !digest(v["artifactDigest"]) ||
        !sha(v["sourceCommit"]) ||
        !id(v["approvedEvidenceId"])
    ) {
        return RefResult.Err(path)
    }
    return RefResult.Ok(
        mapOf(
            "releaseId" to v["releaseId"],
            "artifactDigest" to (v["artifactDigest"] as String).lowercase(),
            "sourceCommit" to (v["sourceCommit"] as String).lowercase(),
            "approvedEvidenceId" to v["approvedEvidenceId"],
        ),
    )
}

private fun operationRef(value: Any?, path: String, kind: String): RefResult<Map<String, Any?>> {
    val v = value as? Map<*, *> ?: return RefResult.Err(path)
    if (!exactKeys(v, listOf("operationId", "kind", "state", "targetHash", "sourceCommit", "artifactDigest")) ||
        !id(v["operationId"]) ||
        v["kind"] != kind ||
        v["state"] != "succeeded" ||
        !digest(v["targetHash"]) ||
        !sha(v["sourceCommit"]) ||
        !digest(v["artifactDigest"])
    ) {
        return RefResult.Err(path)
    }
    return RefResult.Ok(
        mapOf(
            "operationId" to v["operationId"],
            "kind" to v["kind"],
            "state" to v["state"],
            "targetHash" to (v["targetHash"] as String).lowercase(),
            "sourceCommit" to (v["sourceCommit"] as String).lowercase(),
            "artifactDigest" to (v["artifactDigest"] as String).lowercase(),
        ),
    )
}
