package io.whozoss.factory.planchange.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.planchange.domain.PlanChangeDecisionStatus
import io.whozoss.factory.planchange.domain.PlanChangeValidation
import io.whozoss.factory.planchange.domain.invalidPlanChangeProposal
import io.whozoss.factory.planchange.domain.invalidPlanChangeQuery
import io.whozoss.factory.planchange.service.PlanChangeProposalService
import io.whozoss.factory.web.FactoryDataEnvelope
import io.whozoss.factory.web.TrustContext
import io.whozoss.factory.web.resolveFactoryCaller
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * HTTP boundary of the governed replanning aggregate (Phase 8):
 * `/api/factory/plan-change-proposals`.
 *
 * The Workstream Agent (and the human control-plane) propose plan changes here;
 * the Factory validates, classifies deterministically, gates per the governance
 * Rules 1–3 and persists immutable, append-only records. Nothing on this surface
 * ever rewrites an active workflow instance silently.
 *
 * The call is scoped by the verified [TrustContext] resolved at the boundary
 * (`resolveFactoryCaller`, failing closed with 401 `TRUST_CONTEXT_UNAVAILABLE`) —
 * the organization/workstream scope is never read from client input; the
 * `namespaceId` is supplied by the request and strictly validated. Success
 * responses use the canonical `{ "data": ... }` envelope; failures are rendered
 * by [io.whozoss.factory.error.FactoryExceptionHandler].
 */
@RestController
@RequestMapping("/api/factory/plan-change-proposals")
@Tag(name = "plan-change-proposals", description = "Governed replanning plan-change proposals")
class PlanChangeProposalController(
    private val service: PlanChangeProposalService,
    private val tenantScopeProvider: TenantScopeProvider,
) {

    @PostMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Submit a plan-change proposal",
        description = "Validates the bounded payload, computes the deterministic classification and " +
            "recommended governance verdict, and persists the immutable proposal. Idempotent on " +
            "(organizationId, workstreamId, workflowId, idempotencyKey).",
    )
    fun submit(
        @RequestBody(required = false) body: SubmitPlanChangeRequest?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<FactoryDataEnvelope<PlanChangeProposalResponse>> {
        val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)
        val request = body ?: invalidPlanChangeProposal("A JSON body is required")
        val result = service.submit(caller.scope, caller.actorId, request.toCommand())
        val status = if (result.idempotent) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).body(FactoryDataEnvelope(result.toResponse()))
    }

    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "List the plan-change proposals of a workflow, optionally filtered by status.")
    fun list(
        @RequestParam(name = "workflowId", required = false) workflowId: String?,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @RequestParam(name = "status", required = false) status: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): FactoryDataEnvelope<List<PlanChangeProposalResponse>> {
        val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)
        val scopedNamespaceId = PlanChangeValidation.requireNamespaceId(namespaceId)
        val scopedWorkflowId = requireWorkflowQuery(workflowId)
        val statusFilter = status?.takeIf { it.isNotBlank() }?.let {
            PlanChangeDecisionStatus.parse(it)
                ?: invalidPlanChangeQuery(
                    "Unknown status filter '$it' (expected one of " +
                        PlanChangeDecisionStatus.entries.joinToString(", ") { entry -> entry.dbValue } + ")",
                )
        }
        val proposals = service.list(caller.scope, scopedNamespaceId, scopedWorkflowId, statusFilter)
        return FactoryDataEnvelope(
            proposals.map { it.toResponse(decisions = emptyList(), idempotent = false) },
        )
    }

    @GetMapping("/{proposalId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Read one plan-change proposal with its full immutable decision timeline.")
    fun get(
        @PathVariable proposalId: String,
        @RequestParam(name = "workflowId", required = false) workflowId: String?,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): FactoryDataEnvelope<PlanChangeProposalResponse> {
        val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)
        val scopedNamespaceId = PlanChangeValidation.requireNamespaceId(namespaceId)
        val scopedWorkflowId = requireWorkflowQuery(workflowId)
        val result = service.get(caller.scope, scopedNamespaceId, scopedWorkflowId, proposalId)
        return FactoryDataEnvelope(result.toResponse())
    }

    @PostMapping("/{proposalId}/decide", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Record a governance decision on a plan-change proposal",
        description = "Appends an immutable decision event (AUTO_APPLIED, GATE_REQUIRED, " +
            "REQUIRES_NEW_DEFINITION or REJECTED). Decisions violating the governance Rules 1–3 " +
            "are rejected with 409 PLAN_CHANGE_GATE_REQUIRED.",
    )
    fun decide(
        @PathVariable proposalId: String,
        @RequestParam(name = "workflowId", required = false) workflowId: String?,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @RequestBody(required = false) body: DecidePlanChangeRequest?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): FactoryDataEnvelope<PlanChangeProposalResponse> {
        val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)
        val scopedNamespaceId = PlanChangeValidation.requireNamespaceId(namespaceId)
        val scopedWorkflowId = requireWorkflowQuery(workflowId)
        val request = body ?: invalidPlanChangeProposal("A JSON body is required")
        val result = service.decide(
            caller.scope,
            caller.actorId,
            scopedNamespaceId,
            scopedWorkflowId,
            proposalId,
            request.toCommand(),
        )
        return FactoryDataEnvelope(result.toResponse())
    }

    /** Require the `workflowId` query parameter (400 `INVALID_PLAN_CHANGE_QUERY`). */
    private fun requireWorkflowQuery(workflowId: String?): String =
        workflowId?.takeIf { it.isNotBlank() }
            ?: invalidPlanChangeQuery("workflowId query param is required")
}
