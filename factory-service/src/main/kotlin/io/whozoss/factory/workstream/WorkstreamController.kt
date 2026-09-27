package io.whozoss.factory.workstream

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext
import io.whozoss.factory.web.requireNamespaceQuery
import io.whozoss.factory.web.resolveFactoryCaller
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Workstream query/creation surface.
 *
 * Port of `factory/dashboard/workstream-routes.mjs`, backed by the existing
 * tenant-scoped `workstreams` table (never recreated here).
 */
@RestController
@RequestMapping("/api/factory/workstreams")
@Tag(name = "workstreams", description = "Factory workstreams")
class WorkstreamController(
    private val service: WorkstreamService,
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
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<Map<String, Any?>> {
        val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)
        val request = body ?: emptyMap()
        requireNamespaceQuery(request["namespaceId"] as? String)
        val created = service.create(
            scope = caller.scope,
            slug = request["slug"] as? String,
            name = request["name"] as? String,
            status = request["status"] as? String,
        )
        return ResponseEntity.status(201).body(created)
    }
}
