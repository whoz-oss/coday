package io.whozoss.factory.workstream

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.error.ResourceNotFoundException
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext
import io.whozoss.factory.web.requireNamespaceQuery
import io.whozoss.factory.web.resolveFactoryCaller
import io.whozoss.factory.workstream.domain.ControllerCaseStatus
import io.whozoss.factory.workstream.projection.WorkstreamProjectionService
import io.whozoss.factory.workstream.web.CompactControllerCaseRequest
import io.whozoss.factory.workstream.web.ControllerCaseHistoryResponse
import io.whozoss.factory.workstream.web.ControllerCaseResponse
import io.whozoss.factory.workstream.web.ControllerResumptionPackage
import io.whozoss.factory.workstream.web.CreateWorkstreamRequest
import io.whozoss.factory.workstream.web.StartControllerCaseRequest
import io.whozoss.factory.workstream.web.UpdateWorkstreamRequest
import io.whozoss.factory.workstream.web.WorkstreamProjectionResponse
import io.whozoss.factory.workstream.web.toResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Workstream query/creation surface.
 *
 * Backed by the existing tenant-scoped `workstreams` table (never recreated
 * here), enriched into a
 * versioned registry (Phase 5) with a read-only aggregated projection
 * (`GET /{workstreamId}/projection`) carrying a stable ETag revision.
 */
@RestController
@RequestMapping("/api/factory/workstreams")
@Tag(name = "workstreams", description = "Factory workstreams")
class WorkstreamController(
    private val service: WorkstreamService,
    private val projectionService: WorkstreamProjectionService,
    private val controllerCaseService: ControllerCaseService,
    private val tenantScopeProvider: TenantScopeProvider,
) {

    @GetMapping
    @Operation(summary = "List workstreams for the caller's tenant.")
    fun list(
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): List<Map<String, Any?>> {
        val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)
        requireNamespaceQuery(namespaceId)
        return service.list(caller.scope)
    }

    @PostMapping
    @Operation(summary = "Create a workstream entry.")
    fun create(
        @RequestBody(required = false) body: CreateWorkstreamRequest?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<Map<String, Any?>> {
        val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)
        val request = body ?: CreateWorkstreamRequest()
        requireNamespaceQuery(request.namespaceId)
        val created = service.create(caller.scope, request)
        return ResponseEntity.status(201).body(created)
    }

    @GetMapping("/{workstreamId}")
    @Operation(summary = "Read one workstream registry entry of the caller's trusted workstream.")
    fun get(
        @PathVariable workstreamId: String,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): Map<String, Any?> {
        val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)
        service.assertWithinWorkstream(caller, workstreamId)
        return service.get(caller.scope, workstreamId)
    }

    @PutMapping("/{workstreamId}")
    @Operation(summary = "Save a new revision of a workstream registry entry.")
    fun update(
        @PathVariable workstreamId: String,
        @RequestBody(required = false) body: UpdateWorkstreamRequest?,
        @RequestHeader(name = "If-Match", required = false) ifMatch: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): Map<String, Any?> {
        val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)
        service.assertWithinWorkstream(caller, workstreamId)
        val request = body ?: UpdateWorkstreamRequest()
        val expectedRevision = request.expectedRevision ?: parseIfMatch(ifMatch)
        return service.update(caller.scope, workstreamId, request, expectedRevision)
    }

    @GetMapping("/{workstreamId}/projection")
    @Operation(summary = "Read-only aggregated projection of the caller's trusted workstream.")
    fun projection(
        @PathVariable workstreamId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @RequestParam(name = "limit", required = false) limit: Int?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<WorkstreamProjectionResponse> {
        val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)
        val dto = projectionService.getAggregatedProjection(caller, workstreamId, namespaceId, limit)
        return ResponseEntity
            .ok()
            .eTag("\"${dto.workstreamRevision}\"")
            .body(dto)
    }

    /**
     * The active controller case of the workstream (Cockpit). The contract is
     * explicit: when no case is active yet, the answer is a 404
     * `NO_ACTIVE_CONTROLLER_CASE`, never an empty 200.
     */
    @GetMapping("/{workstreamId}/controller-case")
    @Operation(summary = "Read the active controller case of the caller's trusted workstream.")
    fun activeControllerCase(
        @PathVariable workstreamId: String,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ControllerCaseResponse {
        val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)
        val active = controllerCaseService.getActiveCase(caller, workstreamId)
            ?: throw ResourceNotFoundException(
                "Le workstream '$workstreamId' n'a aucun case contrôleur actif",
                mapOf("code" to "NO_ACTIVE_CONTROLLER_CASE"),
            )
        return active.toResponse()
    }

    @GetMapping("/{workstreamId}/controller-case/history")
    @Operation(summary = "List the controller case history (active + archived) of the caller's trusted workstream.")
    fun controllerCaseHistory(
        @PathVariable workstreamId: String,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ControllerCaseHistoryResponse {
        val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)
        val history = controllerCaseService.listHistory(caller, workstreamId)
        return ControllerCaseHistoryResponse(
            workstreamId = workstreamId,
            activeCaseId = history.firstOrNull { it.status == ControllerCaseStatus.ACTIVE }?.caseId,
            cases = history.map { it.toResponse() },
        )
    }

    /**
     * Preview the bounded resumption context package that a new controller
     * case would be seeded with, rebuilt live from the aggregated projection.
     * Read-only: nothing is persisted.
     */
    @GetMapping("/{workstreamId}/controller-case/context")
    @Operation(summary = "Preview the bounded controller resumption context package of the caller's trusted workstream.")
    fun controllerCaseContext(
        @PathVariable workstreamId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @RequestParam(name = "limit", required = false) limit: Int?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ControllerResumptionPackage {
        val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)
        return controllerCaseService.getContextPackage(caller, workstreamId, namespaceId, limit)
    }

    @PostMapping("/{workstreamId}/controller-case")
    @Operation(summary = "Start the first controller case of the caller's trusted workstream.")
    fun startControllerCase(
        @PathVariable workstreamId: String,
        @RequestBody(required = false) body: StartControllerCaseRequest?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<ControllerCaseResponse> {
        val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)
        val started = controllerCaseService.startControllerCase(caller, workstreamId, body ?: StartControllerCaseRequest())
        return ResponseEntity.status(201).body(started.toResponse())
    }

    /**
     * Explicit compaction: archive the active controller case and bind a
     * fresh one — same Workstream Agent identity (`controllerAgentRef`), same
     * workstream identity, next sequence, bounded resumption package rebuilt
     * from the current projection.
     */
    @PostMapping("/{workstreamId}/controller-case/compact")
    @Operation(summary = "Compact / renew the controller case of the caller's trusted workstream.")
    fun compactControllerCase(
        @PathVariable workstreamId: String,
        @RequestBody(required = false) body: CompactControllerCaseRequest?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ControllerCaseResponse {
        val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)
        return controllerCaseService
            .compactControllerCase(caller, workstreamId, body ?: CompactControllerCaseRequest())
            .toResponse()
    }

    /** Parse an `If-Match` header (`"3"` or `3`) into the expected revision. */
    private fun parseIfMatch(ifMatch: String?): Int? =
        ifMatch
            ?.trim()
            ?.removePrefix("W/")
            ?.trim('"', ' ')
            ?.toIntOrNull()
}
