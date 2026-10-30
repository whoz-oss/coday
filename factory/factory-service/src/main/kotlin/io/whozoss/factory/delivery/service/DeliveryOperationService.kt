package io.whozoss.factory.delivery.service

import io.whozoss.factory.delivery.domain.CanonicalHash
import io.whozoss.factory.delivery.domain.DeliveryErrorCodes
import io.whozoss.factory.delivery.domain.DeliveryException
import io.whozoss.factory.delivery.domain.DeliveryOperationObservation
import io.whozoss.factory.delivery.domain.DeliveryOperationPolicyDecision
import io.whozoss.factory.delivery.domain.DeliveryOperationPolicyEvaluation
import io.whozoss.factory.delivery.domain.DeliveryOperationPolicyExistingOperation
import io.whozoss.factory.delivery.domain.DeliveryOperationPolicySnapshot
import io.whozoss.factory.delivery.domain.DeliveryOperationPolicyTarget
import io.whozoss.factory.delivery.domain.DeliveryOperationRequestNormalization
import io.whozoss.factory.delivery.domain.DeliveryOperationScope
import io.whozoss.factory.delivery.domain.DeliveryOperationIdentityDerivation
import io.whozoss.factory.delivery.domain.deriveDeliveryOperationIdentity
import io.whozoss.factory.delivery.domain.deliveryException
import io.whozoss.factory.delivery.domain.evaluateDeliveryOperationPolicy
import io.whozoss.factory.delivery.domain.normalizeDeliveryOperationRequest
import io.whozoss.factory.delivery.persistence.DeliveryRepository
import io.whozoss.factory.delivery.persistence.DeliveryStoreOperationInput
import io.whozoss.factory.delivery.persistence.DeliveryStoreRollbackApprovalInput
import io.whozoss.factory.delivery.persistence.DeliveryStoreRollbackRequestInput
import io.whozoss.factory.delivery.persistence.DeliveryWriteResult
import io.whozoss.factory.delivery.port.DeliveryTarget
import io.whozoss.factory.delivery.port.DeliveryTargetLookup
import io.whozoss.factory.delivery.port.DeliveryTargetRegistry
import io.whozoss.factory.persistence.TenantScope
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val OP_SAFE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
private val OP_REASON = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
private val OP_FORBIDDEN = setOf(
    "targetConfig", "adapterId", "adapterTargetRef", "callbackUrl", "command", "env",
    "credentials", "result", "outcome", "success", "sourceKind", "facts",
)

/**
 * Application service of the DELIVERY OPERATIONS control plane (deployments,
 * production verifications, rollbacks and their approvals).
 *
 * Port of `factory/src/application/delivery/delivery-operation-controller.ts`:
 * every operation flows through the same pipeline — untrusted-input rejection,
 * request normalization, delivery resolution, trusted-target binding and pure
 * policy evaluation — before it is durably created and, using the configured
 * stub adapters, driven to a terminal state.
 */
@Service
class DeliveryOperationService(
    private val repository: DeliveryRepository,
    private val deliveryService: DeliveryService,
    private val targets: DeliveryTargetRegistry,
) {

    /** HTTP status endpoint of the delivery operation control plane. */
    fun status(scope: TenantScope, namespaceId: String, caseId: String, workflowId: String): Map<String, Any?> =
        deliveryService.status(scope, namespaceId, caseId, workflowId)

    /** POST deploy: create and execute a deployment operation through a trusted target. */
    fun deploy(
        scope: TenantScope,
        namespaceId: String,
        caseId: String,
        workflowId: String,
        actorId: String,
        body: Map<String, Any?>?,
    ): DeliveryHttpResult = execute(
        scope,
        namespaceId,
        caseId,
        workflowId,
        actorId,
        body,
        kind = "deployment",
        fields = listOf("expectedRevision", "idempotencyKey", "targetId", "artifactRef", "releaseRef"),
    )

    /** POST verify: create and execute a production-verification operation. */
    fun verify(
        scope: TenantScope,
        namespaceId: String,
        caseId: String,
        workflowId: String,
        actorId: String,
        body: Map<String, Any?>?,
    ): DeliveryHttpResult = execute(
        scope,
        namespaceId,
        caseId,
        workflowId,
        actorId,
        body,
        kind = "production-verification",
        fields = listOf("expectedRevision", "idempotencyKey", "targetId", "deploymentRef"),
    )

    /** POST deployment/reconcile: reconcile a running/indeterminate operation. */
    fun reconcile(
        scope: TenantScope,
        namespaceId: String,
        caseId: String,
        workflowId: String,
        body: Map<String, Any?>?,
    ): DeliveryHttpResult {
        requireOperationBody(body, listOf("operationId", "state"))
        val input = body!!
        val operationId = input["operationId"] as? String
        if (operationId == null || !OP_SAFE.matches(operationId)) {
            throw deliveryException(DeliveryErrorCodes.UNTRUSTED_DELIVERY_INPUT)
        }
        val resolved = deliveryService.resolve(scope, namespaceId, caseId, workflowId)
        val deliveryId = resolved.snapshot["deliveryId"] as String
        val projection = repository.inspectDeliveryOperations(scope, namespaceId, deliveryId)
        val operation = projection.operations.find { it["operationId"] == operationId }
        if (operation == null || operation["state"] !in listOf("running", "indeterminate")) {
            throw DeliveryException(
                "DELIVERY_OPERATION_NOT_RECONCILABLE",
                "Operation is not reconcilable",
                409,
            )
        }
        val result = repository.reconcileDeliveryOperation(
            scope,
            namespaceId,
            deliveryId,
            operationId,
            DeliveryOperationObservation(
                operationId = operationId,
                state = input["state"] as? String ?: "",
                result = input["result"],
                error = input["error"],
            ),
        )
        requireOk(result)
        return DeliveryHttpResult(200, latestOperation(scope, namespaceId, deliveryId, operationId) ?: result.operation)
    }

    /** POST rollbacks: create a rollback request bound to a trusted target. */
    fun requestRollback(
        scope: TenantScope,
        namespaceId: String,
        caseId: String,
        workflowId: String,
        actorId: String,
        body: Map<String, Any?>?,
    ): DeliveryHttpResult {
        val fields = listOf(
            "expectedRevision", "idempotencyKey", "targetId", "deploymentRef",
            "priorArtifactRef", "priorReleaseRef", "reasonCode", "reason",
        )
        if (body == null || body.keys.any { it !in fields } || body.keys.any { it in OP_FORBIDDEN } ||
            !OP_SAFE.matches(body["idempotencyKey"] as? String ?: "") ||
            !OP_SAFE.matches(body["targetId"] as? String ?: "") ||
            !OP_REASON.matches(body["reasonCode"] as? String ?: "")
        ) {
            throw deliveryException(DeliveryErrorCodes.INVALID_DELIVERY_REQUEST)
        }
        val reason = body["reason"] as? String
        if (body.containsKey("reason") && (reason == null || reason.isEmpty() || reason.length > 512)) {
            throw deliveryException(DeliveryErrorCodes.INVALID_DELIVERY_REQUEST)
        }
        val resolved = deliveryService.resolve(scope, namespaceId, caseId, workflowId)
        val target = lookupTarget(body["targetId"] as? String)
        val deliveryId = resolved.snapshot["deliveryId"] as String
        val expectedRevision = (body["expectedRevision"] as? Number)?.toInt()
        if ((resolved.snapshot["revision"] as? Number)?.toInt() != expectedRevision) {
            throw deliveryException(DeliveryErrorCodes.REVISION_CONFLICT)
        }
        val scopeHash = CanonicalHash.canonicalDeliveryHash(
            mapOf(
                "namespaceId" to namespaceId,
                "workflowId" to workflowId,
                "deliveryId" to deliveryId,
                "caseId" to caseId,
                "runtimeId" to "factory-dashboard",
                "idempotencyKey" to body["idempotencyKey"],
            ),
        )
        val semanticHash = CanonicalHash.canonicalDeliveryHash(
            mapOf(
                "targetHash" to target.targetHash,
                "deploymentRef" to body["deploymentRef"],
                "priorArtifactRef" to body["priorArtifactRef"],
                "priorReleaseRef" to body["priorReleaseRef"],
                "reasonCode" to body["reasonCode"],
                "reason" to body["reason"],
                "expectedRevision" to body["expectedRevision"],
            ),
        )
        val request = linkedMapOf<String, Any?>(
            "rollbackRequestId" to "rrq_${scopeHash.drop(7).take(32)}",
            "expectedRevision" to body["expectedRevision"],
            "idempotencyKey" to body["idempotencyKey"],
            "targetId" to body["targetId"],
            "targetHash" to target.targetHash,
            "deploymentRef" to body["deploymentRef"],
            "priorArtifactRef" to body["priorArtifactRef"],
            "priorReleaseRef" to body["priorReleaseRef"],
            "reasonCode" to body["reasonCode"],
            "scopeHash" to scopeHash,
            "semanticHash" to semanticHash,
        )
        (body["reason"] as? String)?.let { request["reason"] = it }
        val result = repository.createRollbackRequest(
            scope,
            DeliveryStoreRollbackRequestInput(
                namespaceId = namespaceId,
                deliveryId = deliveryId,
                workflowId = workflowId,
                caseId = caseId,
                runtimeId = "factory-dashboard",
                request = request,
                execution = execution(namespaceId, caseId, workflowId, actorId),
            ),
        )
        if (!result.ok) throw deliveryException(result.error?.code ?: DeliveryErrorCodes.DELIVERY_CONTROL_PLANE_FAILURE)
        return DeliveryHttpResult(if (result.changed) 201 else 200, result.request)
    }

    /** POST rollbacks/{id}/approve: approve a pending rollback request. */
    fun approveRollback(
        scope: TenantScope,
        namespaceId: String,
        caseId: String,
        workflowId: String,
        actorId: String,
        rollbackRequestId: String,
        body: Map<String, Any?>?,
    ): DeliveryHttpResult {
        requireOperationBody(body, listOf("expectedRevision", "idempotencyKey"))
        if (!OP_SAFE.matches(rollbackRequestId)) {
            throw deliveryException(DeliveryErrorCodes.INVALID_DELIVERY_REQUEST)
        }
        val input = body!!
        if (!OP_SAFE.matches(input["idempotencyKey"] as? String ?: "")) {
            throw deliveryException(DeliveryErrorCodes.INVALID_DELIVERY_REQUEST)
        }
        val resolved = deliveryService.resolve(scope, namespaceId, caseId, workflowId)
        val deliveryId = resolved.snapshot["deliveryId"] as String
        val result = repository.approveRollbackRequest(
            scope,
            namespaceId,
            deliveryId,
            rollbackRequestId,
            DeliveryStoreRollbackApprovalInput(
                expectedRevision = (input["expectedRevision"] as? Number)?.toInt() ?: 0,
                idempotencyKey = input["idempotencyKey"] as String,
                execution = execution(namespaceId, caseId, workflowId, actorId),
            ),
        )
        if (!result.ok) throw deliveryException(result.error?.code ?: DeliveryErrorCodes.DELIVERY_CONTROL_PLANE_FAILURE)
        return DeliveryHttpResult(if (result.changed) 201 else 200, result.request)
    }

    /** POST rollbacks/{id}/execute: create and execute a rollback operation. */
    fun executeRollback(
        scope: TenantScope,
        namespaceId: String,
        caseId: String,
        workflowId: String,
        actorId: String,
        rollbackRequestId: String,
        body: Map<String, Any?>?,
    ): DeliveryHttpResult {
        requireOperationBody(body, listOf("expectedRevision", "idempotencyKey"))
        if (!OP_SAFE.matches(rollbackRequestId)) {
            throw deliveryException(DeliveryErrorCodes.UNTRUSTED_DELIVERY_INPUT)
        }
        val resolved = deliveryService.resolve(scope, namespaceId, caseId, workflowId)
        val deliveryId = resolved.snapshot["deliveryId"] as String
        val projection = repository.inspectDeliveryOperations(scope, namespaceId, deliveryId)
        val rollback = projection.rollbackRequests.find { it["rollbackRequestId"] == rollbackRequestId }
            ?: throw DeliveryException(DeliveryErrorCodes.ROLLBACK_REQUEST_NOT_FOUND, "Rollback request not found", 404)
        if (rollback["status"] != "approved") {
            throw deliveryException(DeliveryErrorCodes.ROLLBACK_APPROVAL_REQUIRED)
        }
        val target = lookupTarget(rollback["targetId"] as? String)
        val request = linkedMapOf<String, Any?>(
            "kind" to "rollback",
            "expectedRevision" to body!!["expectedRevision"],
            "idempotencyKey" to body["idempotencyKey"],
            "targetId" to rollback["targetId"],
            "deploymentRef" to rollback["deploymentRef"],
            "priorArtifactRef" to rollback["priorArtifactRef"],
            "priorReleaseRef" to rollback["priorReleaseRef"],
            "rollbackRequestId" to rollbackRequestId,
            "approvedEvidenceId" to rollback["approvedEvidenceId"],
        )
        return executePrepared(scope, namespaceId, caseId, workflowId, actorId, request, target)
    }

    /** POST rollbacks/{id}/verify: create and execute a rollback-verification operation. */
    fun verifyRollback(
        scope: TenantScope,
        namespaceId: String,
        caseId: String,
        workflowId: String,
        actorId: String,
        rollbackRequestId: String,
        body: Map<String, Any?>?,
    ): DeliveryHttpResult {
        requireOperationBody(body, listOf("expectedRevision", "idempotencyKey", "rollbackRef", "targetId"))
        if (!OP_SAFE.matches(rollbackRequestId)) {
            throw deliveryException(DeliveryErrorCodes.UNTRUSTED_DELIVERY_INPUT)
        }
        val target = lookupTarget(body!!["targetId"] as? String)
        val request = linkedMapOf<String, Any?>(
            "kind" to "rollback-verification",
            "expectedRevision" to body["expectedRevision"],
            "idempotencyKey" to body["idempotencyKey"],
            "targetId" to body["targetId"],
            "rollbackRef" to body["rollbackRef"],
        )
        return executePrepared(scope, namespaceId, caseId, workflowId, actorId, request, target)
    }

    // ------------------------------------------------------------------
    // Internal pipelines
    // ------------------------------------------------------------------

    private fun execute(
        scope: TenantScope,
        namespaceId: String,
        caseId: String,
        workflowId: String,
        actorId: String,
        body: Map<String, Any?>?,
        kind: String,
        fields: List<String>,
    ): DeliveryHttpResult {
        requireOperationBody(body, fields)
        val request = LinkedHashMap(body!!)
        request["kind"] = kind
        val resolved = deliveryService.resolve(scope, namespaceId, caseId, workflowId)
        val normalized = normalizeDeliveryOperationRequest(request)
        if (normalized is DeliveryOperationRequestNormalization.Invalid) {
            throw DeliveryException(normalized.code, normalized.reason, 400)
        }
        val value = (normalized as DeliveryOperationRequestNormalization.Valid).value
        val target = lookupTarget(value.targetId)
        val deliveryId = resolved.snapshot["deliveryId"] as String
        val projection = repository.inspectDeliveryOperations(scope, namespaceId, deliveryId)
        val identity = deriveDeliveryOperationIdentity(
            DeliveryOperationScope(namespaceId, workflowId, deliveryId, caseId, "factory-dashboard"),
            value,
            target.targetHash,
        )
        if (identity is DeliveryOperationIdentityDerivation.Invalid) {
            throw DeliveryException(identity.code, identity.reason, 400)
        }
        val decision = evaluateDeliveryOperationPolicy(
            DeliveryOperationPolicyEvaluation(
                request = value,
                snapshot = resolved.snapshot.toPolicySnapshot(),
                target = target.toPolicyTarget(),
                identityTargetHash = target.targetHash,
                existingOperations = projection.operations.map { it.toExistingOperation() },
            ),
        )
        if (decision is DeliveryOperationPolicyDecision.Denied) {
            throw deliveryException(decision.code, decision.reason)
        }
        return executePrepared(scope, namespaceId, caseId, workflowId, actorId, request, target)
    }

    private fun executePrepared(
        scope: TenantScope,
        namespaceId: String,
        caseId: String,
        workflowId: String,
        actorId: String,
        request: Map<String, Any?>,
        target: DeliveryTarget,
    ): DeliveryHttpResult {
        val resolved = deliveryService.resolve(scope, namespaceId, caseId, workflowId)
        val deliveryId = resolved.snapshot["deliveryId"] as String
        val targetRef = mapOf("targetId" to target.targetId, "targetHash" to target.targetHash)
        val created = repository.createDeliveryOperation(
            scope,
            DeliveryStoreOperationInput(
                namespaceId = namespaceId,
                workflowId = workflowId,
                deliveryId = deliveryId,
                caseId = caseId,
                runtimeId = "factory-dashboard",
                request = request,
                targetRef = targetRef,
                execution = execution(namespaceId, caseId, workflowId, actorId),
            ),
        )
        requireOk(created)
        val operationId = created.operation?.get("operationId") as? String
        if (created.changed && operationId != null) {
            repository.startDeliveryOperation(
                scope,
                namespaceId,
                deliveryId,
                operationId,
                mapOf("adapterId" to target.adapterId, "adapterTargetRef" to target.adapterTargetRef),
            )
            repository.reconcileDeliveryOperation(
                scope,
                namespaceId,
                deliveryId,
                operationId,
                DeliveryOperationObservation(
                    operationId = operationId,
                    state = "succeeded",
                    result = mapOf("adapterId" to target.adapterId, "state" to "succeeded"),
                ),
            )
        }
        val finalOperation = if (operationId != null) {
            latestOperation(scope, namespaceId, deliveryId, operationId) ?: created.operation
        } else {
            created.operation
        }
        return DeliveryHttpResult(if (created.changed) 201 else 200, finalOperation)
    }

    private fun latestOperation(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
        operationId: String,
    ): Map<String, Any?>? = repository.inspectDeliveryOperations(scope, namespaceId, deliveryId)
        .operations.find { it["operationId"] == operationId }

    private fun lookupTarget(targetId: String?): DeliveryTarget = when (val found = targets.lookup(targetId)) {
        is DeliveryTargetLookup.Ok -> found.target
        is DeliveryTargetLookup.NotFound -> {
            val status = if (found.code == DeliveryErrorCodes.DELIVERY_TARGET_REGISTRY_UNAVAILABLE) 503 else 404
            throw DeliveryException(found.code, found.code, status)
        }
    }

    private fun requireOperationBody(body: Map<String, Any?>?, fields: List<String>) {
        if (body == null || body.keys.any { it !in fields } || body.keys.any { it in OP_FORBIDDEN }) {
            throw deliveryException(DeliveryErrorCodes.UNTRUSTED_DELIVERY_INPUT)
        }
    }

    private fun requireOk(result: DeliveryWriteResult) {
        if (!result.ok) {
            throw deliveryException(result.error?.code ?: DeliveryErrorCodes.DELIVERY_CONTROL_PLANE_FAILURE)
        }
    }

    private fun execution(
        namespaceId: String,
        caseId: String,
        workflowId: String,
        actorId: String,
    ): Map<String, Any?> = mapOf(
        "kind" to "factory-control-plane",
        "namespaceId" to namespaceId,
        "workflowId" to workflowId,
        "caseId" to caseId,
        "runtimeId" to "factory-dashboard",
        "actorId" to actorId,
    )
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.toPolicySnapshot(): DeliveryOperationPolicySnapshot = DeliveryOperationPolicySnapshot(
    revision = (this["revision"] as? Number)?.toInt() ?: 0,
    headCommit = this["headCommit"] as String,
    stage = this["stage"] as String,
)

private fun DeliveryTarget.toPolicyTarget(): DeliveryOperationPolicyTarget = DeliveryOperationPolicyTarget(
    targetHash = targetHash,
    supportsRollback = supportsRollback,
    verificationSuiteId = verificationSuiteId,
    verificationSuiteHash = verificationSuiteHash,
)

private fun Map<String, Any?>.toExistingOperation(): DeliveryOperationPolicyExistingOperation =
    DeliveryOperationPolicyExistingOperation(
        state = this["state"] as? String,
        resolvedOperationId = this["resolvedOperationId"] as? String,
    )
