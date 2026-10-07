package io.whozoss.factory.delivery.persistence

import io.whozoss.factory.delivery.domain.DeliveryEvidenceItem
import io.whozoss.factory.delivery.domain.DeliveryExecutionContext
import io.whozoss.factory.delivery.domain.DeliveryOperationObservation
import io.whozoss.factory.delivery.domain.DeliveryPromotionRequest
import io.whozoss.factory.delivery.domain.HashedDeliveryDefinition
import io.whozoss.factory.persistence.TenantScope

/**
 * Persistence port for the delivery context.
 *
 * A delivery is a durable snapshot plus an append-only operations journal
 * projected into live delivery operations and rollback requests. Port of
 * `factory/src/ports/persistence/delivery-repository.ts`: the interface never
 * exposes the SQL layout or hashing details.
 */

/** A store failure carrying a machine code. */
data class DeliveryStoreError(
    val code: String,
    val reason: String? = null,
    val details: Map<String, Any?> = emptyMap(),
)

/** Result of a store write that either changed state or reported a failure. */
data class DeliveryWriteResult(
    val ok: Boolean,
    val changed: Boolean = false,
    val idempotent: Boolean = false,
    val snapshot: Map<String, Any?>? = null,
    val operation: Map<String, Any?>? = null,
    val request: Map<String, Any?>? = null,
    val error: DeliveryStoreError? = null,
) {
    companion object {
        fun changed(
            snapshot: Map<String, Any?>? = null,
            operation: Map<String, Any?>? = null,
            request: Map<String, Any?>? = null,
        ): DeliveryWriteResult = DeliveryWriteResult(ok = true, changed = true, snapshot = snapshot, operation = operation, request = request)

        fun idempotent(
            snapshot: Map<String, Any?>? = null,
            operation: Map<String, Any?>? = null,
            request: Map<String, Any?>? = null,
        ): DeliveryWriteResult = DeliveryWriteResult(ok = true, changed = false, idempotent = true, snapshot = snapshot, operation = operation, request = request)

        fun failure(code: String, reason: String? = null, details: Map<String, Any?> = emptyMap()): DeliveryWriteResult =
            DeliveryWriteResult(ok = false, error = DeliveryStoreError(code, reason, details))
    }
}

/** Projection of the journal into current operations and rollback requests. */
data class DeliveryOperationProjection(
    val history: List<Map<String, Any?>>,
    val operations: List<Map<String, Any?>>,
    val rollbackRequests: List<Map<String, Any?>>,
    val rollbackRequestHistory: List<Map<String, Any?>>,
    val unresolvedIndeterminate: List<Map<String, Any?>>,
)

/** Input of a promotion against the store. */
data class DeliveryStorePromoteInput(
    val namespaceId: String,
    val request: DeliveryPromotionRequest,
    val definition: HashedDeliveryDefinition,
    val evidence: List<DeliveryEvidenceItem>,
    val execution: DeliveryExecutionContext,
)

/** Input of a rollback-request creation against the store. */
data class DeliveryStoreRollbackRequestInput(
    val namespaceId: String,
    val deliveryId: String,
    val workflowId: String,
    val caseId: String,
    val runtimeId: String,
    val request: Map<String, Any?>,
    val execution: Map<String, Any?>,
)

/** Input of a rollback-request approval against the store. */
data class DeliveryStoreRollbackApprovalInput(
    val expectedRevision: Int,
    val idempotencyKey: String,
    val execution: Map<String, Any?>,
)

/** Input of a delivery-operation creation against the store. */
data class DeliveryStoreOperationInput(
    val namespaceId: String,
    val workflowId: String,
    val deliveryId: String,
    val caseId: String,
    val runtimeId: String,
    val request: Map<String, Any?>,
    val targetRef: Map<String, Any?>?,
    val execution: Map<String, Any?>,
)

/** A requested state transition of a persisted delivery operation. */
data class DeliveryOperationTransitionInput(
    val state: String,
    val adapterCorrelation: Any? = null,
    val result: Any? = null,
    val error: Any? = null,
    val resolvedOperationId: Any? = null,
)

interface DeliveryRepository {

    /** The current durable snapshot of one delivery, or `null` when absent. */
    fun read(scope: TenantScope, namespaceId: String, deliveryId: String): Map<String, Any?>?

    /** Creates a delivery snapshot; an identical replay is a no-op. */
    fun create(scope: TenantScope, input: Map<String, Any?>): DeliveryWriteResult

    /** Promotes a delivery against its policy, definition, evidence and execution context. */
    fun promote(scope: TenantScope, input: DeliveryStorePromoteInput): DeliveryWriteResult

    /** The snapshot plus its projected delivery operations and rollback requests. */
    fun readWithOperations(scope: TenantScope, namespaceId: String, deliveryId: String): Map<String, Any?>?

    /** The full projection of the operations journal. */
    fun inspectDeliveryOperations(scope: TenantScope, namespaceId: String, deliveryId: String): DeliveryOperationProjection

    /** Records a rollback request; replays are idempotent on the scope/semantic pair. */
    fun createRollbackRequest(scope: TenantScope, input: DeliveryStoreRollbackRequestInput): DeliveryWriteResult

    /** Approves a pending rollback request at the expected revision. */
    fun approveRollbackRequest(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
        rollbackRequestId: String,
        approval: DeliveryStoreRollbackApprovalInput,
    ): DeliveryWriteResult

    /** Creates a durable delivery operation from a normalized request. */
    fun createDeliveryOperation(scope: TenantScope, input: DeliveryStoreOperationInput): DeliveryWriteResult

    /** Records a state transition of a persisted delivery operation. */
    fun recordDeliveryOperation(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
        operationId: String,
        transition: DeliveryOperationTransitionInput,
        inspectedObservation: DeliveryOperationObservation? = null,
    ): DeliveryWriteResult

    /** Marks a delivery operation as running with the adapter correlation token. */
    fun startDeliveryOperation(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
        operationId: String,
        adapterCorrelation: Any?,
    ): DeliveryWriteResult

    /** Reconciles a delivery operation against an observed adapter outcome. */
    fun reconcileDeliveryOperation(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
        operationId: String,
        observation: DeliveryOperationObservation,
    ): DeliveryWriteResult

    /** Whether the delivery has an unresolved indeterminate operation blocking progress. */
    fun hasIndeterminateOperation(scope: TenantScope, namespaceId: String, deliveryId: String): Boolean

    /** Atomically patches snapshot fields and appends the matching journal record. */
    fun updateSnapshot(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
        patch: Map<String, Any?>,
        operationInput: Map<String, Any?>,
    ): DeliveryWriteResult
}
