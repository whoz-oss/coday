package io.whozoss.factory.delivery.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.delivery.domain.DeliveryErrorCodes
import io.whozoss.factory.delivery.domain.deliveryException
import io.whozoss.factory.delivery.service.DeliveryHttpResult
import io.whozoss.factory.delivery.service.DeliveryService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.web.AuthenticatedUser
import io.whozoss.factory.workflow.web.resolveWorkflowCaller
import io.whozoss.factory.workstream.WorkstreamService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Canonical HTTP success envelope: `{ "data": ... }`. */
data class DeliveryDataEnvelope<T>(
    val data: T,
)

/** The authenticated caller a delivery request is bound to. */
data class DeliveryCaller(
    val scope: TenantScope,
    val namespaceId: String,
    val caseId: String,
    val actorId: String,
)

/**
 * A delivery request: the [caller] plus the request [body] without the
 * `namespaceId` / `caseId` selectors, which the delivery services do not accept.
 */
data class DeliveryRequest(
    val caller: DeliveryCaller,
    val body: Map<String, Any?>?,
)

/**
 * Builds the caller of a delivery request. `namespaceId` and `caseId` are request
 * inputs: body fields on a POST, query parameters on a GET. A missing one is
 * `400 INVALID_DELIVERY_SCOPE`.
 */
fun deliveryRequest(
    workstreamService: WorkstreamService,
    user: AuthenticatedUser,
    body: Map<String, Any?>?,
    namespaceQuery: String? = null,
    caseQuery: String? = null,
): DeliveryRequest {
    val namespaceId = (body?.get("namespaceId") as? String ?: namespaceQuery)?.takeIf { it.isNotBlank() }
        ?: throw deliveryException(DeliveryErrorCodes.INVALID_DELIVERY_SCOPE, "namespaceId is required")
    val caseId = (body?.get("caseId") as? String ?: caseQuery)?.takeIf { it.isNotBlank() }
        ?: throw deliveryException(DeliveryErrorCodes.INVALID_DELIVERY_SCOPE, "caseId is required")
    val serviceBody = body?.filterKeys { it != "namespaceId" && it != "caseId" }
    val resolved = resolveWorkflowCaller(user, namespaceId, workstreamService = workstreamService)
    return DeliveryRequest(DeliveryCaller(resolved.scope, resolved.namespaceId, caseId, user.userId), serviceBody)
}

/**
 * Trusted Factory control-plane for deliveries.
 *
 * Port of `handleDeliveryRequest` in
 * `factory/src/application/delivery/delivery-controller.ts`. All routes live
 * under `/api/factory/workflows/{workflowId}/delivery`. The `namespaceId` and
 * `caseId` are request inputs (body fields, or query parameters on the status
 * GET); a missing one is a `400`. Success responses use the canonical
 * `{ "data": ... }` envelope; failures are rendered by the shared
 * `FactoryExceptionHandler`.
 */
@RestController
@RequestMapping("/api/factory/workflows/{workflowId}/delivery")
@Tag(name = "delivery", description = "Governed delivery control plane")
class DeliveryController(
    private val service: DeliveryService,
    private val workstreamService: WorkstreamService,
) {
    private fun deliveryRequest(
        user: AuthenticatedUser,
        body: Map<String, Any?>?,
        namespaceQuery: String? = null,
        caseQuery: String? = null,
    ): DeliveryRequest = deliveryRequest(workstreamService, user, body, namespaceQuery, caseQuery)


    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Get the current delivery status of a workflow")
    fun status(
        @PathVariable workflowId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @RequestParam(name = "caseId", required = false) caseId: String?,
        @Parameter(hidden = true) user: AuthenticatedUser,
    ): DeliveryDataEnvelope<Any?> {
        val caller = deliveryRequest(user, null, namespaceId, caseId).caller
        return DeliveryDataEnvelope(service.status(caller.scope, caller.namespaceId, caller.caseId, workflowId))
    }

    @PostMapping(path = ["/checkpoint"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Create a git checkpoint commit for the delivery")
    fun checkpoint(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) user: AuthenticatedUser,
    ): ResponseEntity<DeliveryDataEnvelope<Any?>> {
        val (caller, requestBody) = deliveryRequest(user, body)
        return respond(service.checkpoint(caller.scope, caller.namespaceId, caller.caseId, workflowId, requestBody))
    }

    @PostMapping(path = ["/push"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Push the delivery branch to the configured remote")
    fun push(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) user: AuthenticatedUser,
    ): ResponseEntity<DeliveryDataEnvelope<Any?>> {
        val (caller, requestBody) = deliveryRequest(user, body)
        return respond(service.push(caller.scope, caller.namespaceId, caller.caseId, workflowId, requestBody))
    }

    @PostMapping(path = ["/pull-request"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Create a draft pull request for the delivery")
    fun pullRequest(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) user: AuthenticatedUser,
    ): ResponseEntity<DeliveryDataEnvelope<Any?>> {
        val (caller, requestBody) = deliveryRequest(user, body)
        return respond(service.pullRequest(caller.scope, caller.namespaceId, caller.caseId, workflowId, requestBody))
    }

    @PostMapping(path = ["/promote"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Evaluate and apply an ordered, evidence-gated promotion")
    fun promote(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) user: AuthenticatedUser,
    ): ResponseEntity<DeliveryDataEnvelope<Any?>> {
        val (caller, requestBody) = deliveryRequest(user, body)
        return respond(
            service.promote(caller.scope, caller.namespaceId, caller.caseId, workflowId, caller.actorId, requestBody),
        )
    }

    @PostMapping(path = ["/evidence"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Record a Factory delivery evidence fact")
    fun recordEvidence(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) user: AuthenticatedUser,
    ): ResponseEntity<DeliveryDataEnvelope<Any?>> {
        val (caller, requestBody) = deliveryRequest(user, body)
        return respond(
            service.recordEvidence(
                caller.scope,
                caller.namespaceId,
                caller.caseId,
                workflowId,
                caller.actorId,
                requestBody,
                "factory-build",
            ),
        )
    }

    private fun respond(result: DeliveryHttpResult): ResponseEntity<DeliveryDataEnvelope<Any?>> =
        ResponseEntity.status(result.status).body(DeliveryDataEnvelope(result.data))
}
