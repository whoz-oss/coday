package io.whozoss.factory.delivery.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.delivery.domain.DeliveryErrorCodes
import io.whozoss.factory.delivery.domain.DeliveryException
import io.whozoss.factory.delivery.service.DeliveryHttpResult
import io.whozoss.factory.delivery.service.DeliveryService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Canonical HTTP success envelope: `{ "data": ... }`. */
data class DeliveryDataEnvelope<T>(
    val data: T,
)

/** The trusted caller identity a delivery request is bound to. */
data class DeliveryCaller(
    val scope: TenantScope,
    val namespaceId: String,
    val caseId: String,
    val actorId: String,
)

/**
 * Trusted Factory control-plane for deliveries.
 *
 * Port of `handleDeliveryRequest` in
 * `factory/src/application/delivery/delivery-controller.ts`. All routes live
 * under `/api/factory/workflows/{workflowId}/delivery`. The call is scoped by
 * the verified [TrustContext] resolved at the HTTP boundary; a missing or
 * unauthenticated context (or a missing namespace/case identity) fails closed
 * with 401 `TRUST_CONTEXT_UNAVAILABLE`. Success responses use the canonical
 * `{ "data": ... }` envelope; failures are rendered by the shared
 * `FactoryExceptionHandler`.
 */
@RestController
@RequestMapping("/api/factory/workflows/{workflowId}/delivery")
@Tag(name = "delivery", description = "Governed delivery control plane")
class DeliveryController(
    private val service: DeliveryService,
    private val tenantScopeProvider: TenantScopeProvider,
) {

    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Get the current delivery status of a workflow")
    fun status(
        @PathVariable workflowId: String,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): DeliveryDataEnvelope<Any?> {
        val caller = caller(trustContext)
        return DeliveryDataEnvelope(service.status(caller.scope, caller.namespaceId, caller.caseId, workflowId))
    }

    @PostMapping(path = ["/checkpoint"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Create a git checkpoint commit for the delivery")
    fun checkpoint(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<DeliveryDataEnvelope<Any?>> {
        val caller = caller(trustContext)
        return respond(service.checkpoint(caller.scope, caller.namespaceId, caller.caseId, workflowId, body))
    }

    @PostMapping(path = ["/push"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Push the delivery branch to the configured remote")
    fun push(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<DeliveryDataEnvelope<Any?>> {
        val caller = caller(trustContext)
        return respond(service.push(caller.scope, caller.namespaceId, caller.caseId, workflowId, body))
    }

    @PostMapping(path = ["/pull-request"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Create a draft pull request for the delivery")
    fun pullRequest(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<DeliveryDataEnvelope<Any?>> {
        val caller = caller(trustContext)
        return respond(service.pullRequest(caller.scope, caller.namespaceId, caller.caseId, workflowId, body))
    }

    @PostMapping(path = ["/promote"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Evaluate and apply an ordered, evidence-gated promotion")
    fun promote(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<DeliveryDataEnvelope<Any?>> {
        val caller = caller(trustContext)
        return respond(
            service.promote(caller.scope, caller.namespaceId, caller.caseId, workflowId, caller.actorId, body),
        )
    }

    @PostMapping(path = ["/evidence"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Record a Factory delivery evidence fact")
    fun recordEvidence(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<DeliveryDataEnvelope<Any?>> {
        val caller = caller(trustContext)
        return respond(
            service.recordEvidence(
                caller.scope,
                caller.namespaceId,
                caller.caseId,
                workflowId,
                caller.actorId,
                body,
                "factory-build",
            ),
        )
    }

    /** Resolves the trusted caller identity or fails closed with 401. */
    private fun caller(trustContext: TrustContext?): DeliveryCaller {
        val scope = tenantScopeProvider.scopeOf(trustContext)
            ?: throw DeliveryException(
                DeliveryErrorCodes.TRUST_CONTEXT_UNAVAILABLE,
                "Trust context unavailable",
                401,
            )
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
