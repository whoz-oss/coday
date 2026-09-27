package io.whozoss.factory.delivery.domain

/**
 * Pure delivery-operation policy: decides whether a normalized operation
 * request may proceed against the current delivery snapshot and the trusted
 * target, and resolves verification requests against the trusted suite.
 *
 * Faithful port of `factory/src/domain/delivery/delivery-operation-policy.ts`.
 */

/** The snapshot surface the operation policy reads. */
data class DeliveryOperationPolicySnapshot(
    val revision: Int,
    val headCommit: String,
    val stage: String,
)

/** The trusted target surface the operation policy binds on. */
data class DeliveryOperationPolicyTarget(
    val targetHash: String,
    val supportsRollback: Boolean = false,
    val verificationSuiteId: String? = null,
    val verificationSuiteHash: String? = null,
)

/** An existing operation record surface used for indeterminacy checks. */
data class DeliveryOperationPolicyExistingOperation(
    val state: String? = null,
    val resolvedOperationId: String? = null,
)

/** Inputs of an operation policy evaluation. */
data class DeliveryOperationPolicyEvaluation(
    val request: NormalizedDeliveryOperationRequest,
    val snapshot: DeliveryOperationPolicySnapshot?,
    val target: DeliveryOperationPolicyTarget?,
    val identityTargetHash: String? = null,
    val existingOperations: List<DeliveryOperationPolicyExistingOperation> = emptyList(),
)

/** An operation policy decision: allowed, or denied with a code and reason. */
sealed interface DeliveryOperationPolicyDecision {
    data object Allowed : DeliveryOperationPolicyDecision
    data class Denied(val code: String, val reason: String) : DeliveryOperationPolicyDecision
}

/** Evaluates whether an operation request may proceed against the snapshot and target. */
fun evaluateDeliveryOperationPolicy(evaluation: DeliveryOperationPolicyEvaluation): DeliveryOperationPolicyDecision {
    val request = evaluation.request
    val snapshot = evaluation.snapshot
    val target = evaluation.target
    if (snapshot == null) return opDenied("DELIVERY_NOT_FOUND", "delivery_not_found")
    if (snapshot.revision != request.expectedRevision) return opDenied("REVISION_CONFLICT", "stale_delivery_revision")
    if (target == null) return opDenied("DELIVERY_TARGET_NOT_FOUND", "trusted_target_missing")
    if (evaluation.identityTargetHash != target.targetHash) {
        return opDenied("DELIVERY_TARGET_HASH_MISMATCH", "target_binding_mismatch")
    }
    if (evaluation.existingOperations.any { it.state == "indeterminate" && it.resolvedOperationId == null }) {
        return opDenied("DELIVERY_OPERATION_INDETERMINATE", "reconciliation_required")
    }
    val head = snapshot.headCommit
    if (request.artifactRef != null && request.artifactRef["sourceCommit"] != head) {
        return opDenied("SOURCE_COMMIT_MISMATCH", "artifact_not_at_head")
    }
    if (request.releaseRef != null && request.releaseRef["sourceCommit"] != head) {
        return opDenied("SOURCE_COMMIT_MISMATCH", "release_not_at_head")
    }
    if (request.kind == "deployment" && snapshot.stage != "release-approved") {
        return opDenied("RELEASE_NOT_APPROVED", "release_approved_stage_required")
    }
    if (request.kind == "production-verification") {
        if (snapshot.stage != "deployed" || request.deploymentRef?.get("state") != "succeeded") {
            return opDenied("SUCCESSFUL_DEPLOYMENT_REQUIRED", "linked_deployment_required")
        }
        if (request.deploymentRef["targetHash"] != target.targetHash ||
            request.deploymentRef["sourceCommit"] != head
        ) {
            return opDenied("DEPLOYMENT_SCOPE_MISMATCH", "deployment_binding_mismatch")
        }
        if (target.verificationSuiteId == null || target.verificationSuiteHash == null) {
            return opDenied("VERIFICATION_SUITE_NOT_CONFIGURED", "trusted_suite_required")
        }
    }
    if (request.kind == "rollback") {
        if (request.deploymentRef?.get("state") != "succeeded") {
            return opDenied("SUCCESSFUL_DEPLOYMENT_REQUIRED", "linked_deployment_required")
        }
        if (!target.supportsRollback) return opDenied("ROLLBACK_NOT_SUPPORTED", "target_disallows_rollback")
        if (request.approvedEvidenceId == null) {
            return opDenied("ROLLBACK_APPROVAL_REQUIRED", "approval_evidence_required")
        }
        if (request.priorArtifactRef?.get("digest") == request.deploymentRef.get("artifactDigest")) {
            return opDenied("ROLLBACK_RELEASE_UNCHANGED", "prior_release_must_differ")
        }
        if (request.deploymentRef["targetHash"] != target.targetHash ||
            request.deploymentRef["sourceCommit"] != head
        ) {
            return opDenied("DEPLOYMENT_SCOPE_MISMATCH", "deployment_binding_mismatch")
        }
    }
    if (request.kind == "rollback-verification") {
        if (request.rollbackRef?.get("state") != "succeeded") {
            return opDenied("SUCCESSFUL_ROLLBACK_REQUIRED", "linked_rollback_required")
        }
        if (request.rollbackRef["targetHash"] != target.targetHash) {
            return opDenied("ROLLBACK_SCOPE_MISMATCH", "rollback_binding_mismatch")
        }
        if (target.verificationSuiteId == null || target.verificationSuiteHash == null) {
            return opDenied("VERIFICATION_SUITE_NOT_CONFIGURED", "trusted_suite_required")
        }
    }
    return DeliveryOperationPolicyDecision.Allowed
}

private fun opDenied(code: String, reason: String): DeliveryOperationPolicyDecision =
    DeliveryOperationPolicyDecision.Denied(code, reason)

/** Result of binding a verification request to its trusted suite. */
sealed interface DeliveryVerificationResolution {
    data class Valid(val value: Map<String, Any?>) : DeliveryVerificationResolution
    data class Invalid(val code: String = "VERIFICATION_SUITE_NOT_CONFIGURED") : DeliveryVerificationResolution
}

/** Binds a verification request to the trusted suite of the target. */
fun resolveDeliveryVerificationRequest(
    request: Map<String, Any?>,
    target: DeliveryOperationPolicyTarget?,
): DeliveryVerificationResolution {
    if (target == null || target.verificationSuiteId == null || target.verificationSuiteHash == null) {
        return DeliveryVerificationResolution.Invalid()
    }
    return DeliveryVerificationResolution.Valid(
        request + mapOf(
            "verificationSuiteRef" to mapOf(
                "suiteId" to target.verificationSuiteId,
                "suiteHash" to target.verificationSuiteHash,
            ),
        ),
    )
}
