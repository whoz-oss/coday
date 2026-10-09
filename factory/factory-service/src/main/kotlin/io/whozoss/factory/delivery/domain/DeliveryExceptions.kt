package io.whozoss.factory.delivery.domain

import io.whozoss.factory.error.FactoryException

/**
 * Machine-readable error codes of the delivery aggregate.
 *
 * Vocabulary mirrors the Node delivery domain (`delivery-policy.ts`,
 * `delivery-operation-definition.ts`, `sql-delivery-repository.ts` and the
 * delivery controllers) so a client keeps the exact same error semantics across
 * the Node and Kotlin control planes.
 */
object DeliveryErrorCodes {
    const val INVALID_DELIVERY_DEFINITION = "INVALID_DELIVERY_DEFINITION"
    const val INVALID_DELIVERY_SCOPE = "INVALID_DELIVERY_SCOPE"
    const val INVALID_DELIVERY_REQUEST = "INVALID_DELIVERY_REQUEST"
    const val INVALID_DELIVERY_SNAPSHOT = "INVALID_DELIVERY_SNAPSHOT"
    const val INVALID_DELIVERY_EVIDENCE = "INVALID_DELIVERY_EVIDENCE"
    const val DELIVERY_IDENTITY_CONFLICT = "DELIVERY_IDENTITY_CONFLICT"
    const val DELIVERY_NOT_FOUND = "DELIVERY_NOT_FOUND"
    const val DELIVERY_BINDING_UNAVAILABLE = "DELIVERY_BINDING_UNAVAILABLE"
    const val REVISION_CONFLICT = "REVISION_CONFLICT"
    const val DELIVERY_SCOPE_MISMATCH = "DELIVERY_SCOPE_MISMATCH"
    const val DELIVERY_DEFINITION_MISMATCH = "DELIVERY_DEFINITION_MISMATCH"
    const val ILLEGAL_PROMOTION = "ILLEGAL_PROMOTION"
    const val ACTOR_NOT_AUTHORIZED = "ACTOR_NOT_AUTHORIZED"
    const val EVIDENCE_NOT_FOUND = "EVIDENCE_NOT_FOUND"
    const val EVIDENCE_SCOPE_MISMATCH = "EVIDENCE_SCOPE_MISMATCH"
    const val PASS_EVIDENCE_REQUIRED = "PASS_EVIDENCE_REQUIRED"
    const val HUMAN_APPROVAL_REQUIRED = "HUMAN_APPROVAL_REQUIRED"
    const val RELEASE_NOT_APPROVED = "RELEASE_NOT_APPROVED"
    const val SMOKE_PASS_REQUIRED = "SMOKE_PASS_REQUIRED"
    const val IDEMPOTENCY_KEY_COLLISION = "IDEMPOTENCY_KEY_COLLISION"
    const val PULL_REQUEST_NOT_CONFIGURED = "PULL_REQUEST_NOT_CONFIGURED"
    const val UNTRUSTED_DELIVERY_INPUT = "UNTRUSTED_DELIVERY_INPUT"
    const val INVALID_TRUST_CONTEXT = "INVALID_TRUST_CONTEXT"
    const val TRUST_CONTEXT_UNAVAILABLE = "TRUST_CONTEXT_UNAVAILABLE"
    const val STALE_HEAD = "STALE_HEAD"
    const val DELIVERY_HEAD_RECONCILIATION_REQUIRED = "DELIVERY_HEAD_RECONCILIATION_REQUIRED"
    const val DELIVERY_INDETERMINATE_OPERATION_PENDING = "DELIVERY_INDETERMINATE_OPERATION_PENDING"
    const val DELIVERY_CONTROL_PLANE_FAILURE = "DELIVERY_CONTROL_PLANE_FAILURE"

    // Delivery-operation vocabulary (delivery-operation-definition.ts).
    const val INVALID_DELIVERY_OPERATION_REQUEST = "INVALID_DELIVERY_OPERATION_REQUEST"
    const val INVALID_DELIVERY_OPERATION_RECORD = "INVALID_DELIVERY_OPERATION_RECORD"
    const val INVALID_DELIVERY_OPERATION_TRANSITION = "INVALID_DELIVERY_OPERATION_TRANSITION"
    const val DELIVERY_OPERATION_RECONCILIATION_REQUIRED = "DELIVERY_OPERATION_RECONCILIATION_REQUIRED"
    const val DELIVERY_OPERATION_NOT_FOUND = "DELIVERY_OPERATION_NOT_FOUND"
    const val DELIVERY_OPERATION_INDETERMINATE = "DELIVERY_OPERATION_INDETERMINATE"
    const val DELIVERY_TARGET_NOT_FOUND = "DELIVERY_TARGET_NOT_FOUND"
    const val DELIVERY_TARGET_HASH_MISMATCH = "DELIVERY_TARGET_HASH_MISMATCH"
    const val DELIVERY_TARGET_REGISTRY_UNAVAILABLE = "DELIVERY_TARGET_REGISTRY_UNAVAILABLE"
    const val SOURCE_COMMIT_MISMATCH = "SOURCE_COMMIT_MISMATCH"
    const val SUCCESSFUL_DEPLOYMENT_REQUIRED = "SUCCESSFUL_DEPLOYMENT_REQUIRED"
    const val DEPLOYMENT_SCOPE_MISMATCH = "DEPLOYMENT_SCOPE_MISMATCH"
    const val VERIFICATION_SUITE_NOT_CONFIGURED = "VERIFICATION_SUITE_NOT_CONFIGURED"
    const val ROLLBACK_NOT_SUPPORTED = "ROLLBACK_NOT_SUPPORTED"
    const val ROLLBACK_APPROVAL_REQUIRED = "ROLLBACK_APPROVAL_REQUIRED"
    const val ROLLBACK_RELEASE_UNCHANGED = "ROLLBACK_RELEASE_UNCHANGED"
    const val ROLLBACK_SCOPE_MISMATCH = "ROLLBACK_SCOPE_MISMATCH"
    const val SUCCESSFUL_ROLLBACK_REQUIRED = "SUCCESSFUL_ROLLBACK_REQUIRED"
    const val ROLLBACK_REQUEST_NOT_FOUND = "ROLLBACK_REQUEST_NOT_FOUND"
    const val ROLLBACK_REQUEST_ALREADY_DECIDED = "ROLLBACK_REQUEST_ALREADY_DECIDED"
    const val DELIVERY_ADAPTER_NOT_CONFIGURED = "DELIVERY_ADAPTER_NOT_CONFIGURED"
}

/**
 * Delivery failure carrying a stable machine code.
 *
 * Extends [FactoryException] so the canonical `{ "error": { code, message, details } }`
 * envelope is rendered by the shared `FactoryExceptionHandler`, exactly like the
 * Node delivery control plane.
 */
class DeliveryException(
    errorCode: String,
    message: String = errorCode,
    statusCode: Int = 409,
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(statusCode, errorCode, message, details, cause)

/** A pure-domain denial: machine code plus an optional reason. */
data class DeliveryFailure(
    val code: String,
    val reason: String? = null,
)

/** Maps a machine code to the HTTP status the Node control plane returns. */
fun deliveryStatusCode(code: String): Int = when (code) {
    DeliveryErrorCodes.INVALID_DELIVERY_DEFINITION,
    DeliveryErrorCodes.INVALID_DELIVERY_SCOPE,
    DeliveryErrorCodes.INVALID_DELIVERY_REQUEST,
    DeliveryErrorCodes.INVALID_DELIVERY_SNAPSHOT,
    DeliveryErrorCodes.INVALID_DELIVERY_EVIDENCE,
    DeliveryErrorCodes.INVALID_TRUST_CONTEXT,
    DeliveryErrorCodes.UNTRUSTED_DELIVERY_INPUT,
    DeliveryErrorCodes.INVALID_DELIVERY_OPERATION_REQUEST,
    DeliveryErrorCodes.INVALID_DELIVERY_OPERATION_RECORD,
    DeliveryErrorCodes.INVALID_DELIVERY_OPERATION_TRANSITION,
    -> 400
    DeliveryErrorCodes.TRUST_CONTEXT_UNAVAILABLE -> 401
    DeliveryErrorCodes.DELIVERY_NOT_FOUND,
    DeliveryErrorCodes.EVIDENCE_NOT_FOUND,
    DeliveryErrorCodes.DELIVERY_OPERATION_NOT_FOUND,
    DeliveryErrorCodes.ROLLBACK_REQUEST_NOT_FOUND,
    DeliveryErrorCodes.DELIVERY_TARGET_NOT_FOUND,
    -> 404
    DeliveryErrorCodes.PULL_REQUEST_NOT_CONFIGURED,
    DeliveryErrorCodes.DELIVERY_ADAPTER_NOT_CONFIGURED,
    -> 422
    else -> 409
}

/** Builds a [DeliveryException] for a machine code, choosing the Node HTTP status. */
fun deliveryException(code: String, message: String = code, details: Any? = null): DeliveryException =
    DeliveryException(code, message, deliveryStatusCode(code), details)
