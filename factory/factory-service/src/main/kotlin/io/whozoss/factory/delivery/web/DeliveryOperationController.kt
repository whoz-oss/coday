package io.whozoss.factory.delivery.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.delivery.domain.DeliveryErrorCodes
import io.whozoss.factory.delivery.domain.DeliveryException
import io.whozoss.factory.delivery.service.DeliveryHttpResult
import io.whozoss.factory.delivery.service.DeliveryOperationService
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Trusted Factory control-plane for delivery operations (deployments,
 * production verifications, rollbacks and their approvals).
 *
 * Port of `factory/src/application/delivery/delivery-operation-controller.ts`.
 * Every operation flows through the shared prepare pipeline (untrusted-input
 * rejection, request normalization, delivery resolution, trusted-target binding
 * and pure policy evaluation) before it is durably created and executed through
 * the configured adapters. All routes live under
 * `/api/factory/workflows/{workflowId}/delivery`.
 */
@RestController
@RequestMapping("/api/factory/workflows/{workflowId}/delivery")
@Tag(name = "delivery-operations", description = "Delivery deployment, verification and rollback control plane")
class DeliveryOperationController(
    private val service: DeliveryOperationService,
    private val tenantScopeProvider: TenantScopeProvider,
) {

    @PostMapping(path = ["/deploy"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Create and execute a deployment operation")
    fun deploy(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<DeliveryDataEnvelope<Any?>> {
        val caller = caller(trustContext)
        return respond(service.deploy(caller.scope, caller.namespaceId, caller.caseId, workflowId, caller.actorId, body))
    }

    @PostMapping(path = ["/verify"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Create and execute a production-verification operation")
    fun verify(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<DeliveryDataEnvelope<Any?>> {
        val caller = caller(trustContext)
        return respond(service.verify(caller.scope, caller.namespaceId, caller.caseId, workflowId, caller.actorId, body))
    }

    @PostMapping(path = ["/deployment/reconcile"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Reconcile a running or indeterminate delivery operation")
    fun reconcile(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<DeliveryDataEnvelope<Any?>> {
        val caller = caller(trustContext)
        return respond(service.reconcile(caller.scope, caller.namespaceId, caller.caseId, workflowId, body))
    }

    @PostMapping(path = ["/rollbacks"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Create a rollback request bound to a trusted target")
    fun requestRollback(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<DeliveryDataEnvelope<Any?>> {
        val caller = caller(trustContext)
        return respond(
            service.requestRollback(caller.scope, caller.namespaceId, caller.caseId, workflowId, caller.actorId, body),
        )
    }

    @PostMapping(path = ["/rollbacks/{rollbackRequestId}/approve"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Approve a pending rollback request")
    fun approveRollback(
        @PathVariable workflowId: String,
        @PathVariable rollbackRequestId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<DeliveryDataEnvelope<Any?>> {
        val caller = caller(trustContext)
        return respond(
            service.approveRollback(
                caller.scope,
                caller.namespaceId,
                caller.caseId,
                workflowId,
                caller.actorId,
                rollbackRequestId,
                body,
            ),
        )
    }

    @PostMapping(path = ["/rollbacks/{rollbackRequestId}/execute"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Create and execute a rollback operation")
    fun executeRollback(
        @PathVariable workflowId: String,
        @PathVariable rollbackRequestId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<DeliveryDataEnvelope<Any?>> {
        val caller = caller(trustContext)
        return respond(
            service.executeRollback(
                caller.scope,
                caller.namespaceId,
                caller.caseId,
                workflowId,
                caller.actorId,
                rollbackRequestId,
                body,
            ),
        )
    }

    @PostMapping(path = ["/rollbacks/{rollbackRequestId}/verify"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Create and execute a rollback-verification operation")
    fun verifyRollback(
        @PathVariable workflowId: String,
        @PathVariable rollbackRequestId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<DeliveryDataEnvelope<Any?>> {
        val caller = caller(trustContext)
        return respond(
            service.verifyRollback(
                caller.scope,
                caller.namespaceId,
                caller.caseId,
                workflowId,
                caller.actorId,
                rollbackRequestId,
                body,
            ),
        )
    }

    private fun caller(trustContext: TrustContext?): DeliveryCaller {
        val scope = tenantScopeProvider.scopeOf(trustContext)
            ?: throw DeliveryException(DeliveryErrorCodes.TRUST_CONTEXT_UNAVAILABLE, "Trust context unavailable", 401)
        val namespaceId = trustContext?.namespaceId?.takeIf { it.isNotBlank() }
            ?: throw DeliveryException(DeliveryErrorCodes.TRUST_CONTEXT_UNAVAILABLE, "Namespace identity required", 401)
        val caseId = trustContext.caseId?.takeIf { it.isNotBlank() }
            ?: throw DeliveryException(DeliveryErrorCodes.TRUST_CONTEXT_UNAVAILABLE, "Case identity required", 401)
        val actorId = trustContext.principalId?.takeIf { it.isNotBlank() } ?: "factory-operator"
        return DeliveryCaller(scope, namespaceId, caseId, actorId)
    }

    private fun respond(result: DeliveryHttpResult): ResponseEntity<DeliveryDataEnvelope<Any?>> =
        ResponseEntity.status(result.status).body(DeliveryDataEnvelope(result.data))
}
