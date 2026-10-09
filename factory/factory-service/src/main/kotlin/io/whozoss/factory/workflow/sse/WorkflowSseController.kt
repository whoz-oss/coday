package io.whozoss.factory.workflow.sse

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext
import io.whozoss.factory.workflow.web.resolveWorkflowCaller
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Server-Sent Events stream of projection invalidations.
 *
 * Factory workflow projection stream consumed by Cockpit V2:
 *
 * ```http
 * GET /api/factory/workflows/stream?namespaceId=<uuid>
 * Accept: text/event-stream
 * ```
 *
 * The route is `@Operation(hidden = true)` so it is excluded from the generated
 * OpenAPI client (an infinite event stream is not a request/response operation).
 *
 * ## Explicit scope contract
 *
 * The subscription is always partitioned by the *verified* tenant scope derived
 * from the trust context ([resolveWorkflowCaller]) — the scope is never read
 * from client input. `namespaceId` is an optional filter that only narrows the
 * subscription *within* that tenant:
 *
 *  - a concrete namespace → that namespace's invalidations (plus the tenant-wide
 *    invalidation feed);
 *  - absent/blank → the whole tenant scope, keyed by the caller's
 *    `(organizationId, workstreamId)` — never an unpartitioned, cross-tenant
 *    global bucket.
 *
 * A missing/unauthorized trust context fails closed with `401` before any SSE
 * response is opened, so no connection is ever registered without a scope.
 */
@RestController
@RequestMapping("/api/factory/workflows")
@Tag(name = "workflows", description = "Workflow projections and lifecycle")
class WorkflowSseController(
    private val hub: WorkflowSseHub,
    private val tenantScopeProvider: TenantScopeProvider,
) {

    @GetMapping(path = ["/stream"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(hidden = true)
    fun stream(
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
        response: HttpServletResponse,
    ): SseEmitter {
        // Identity + tenant scope are resolved from the verified trust context; a
        // missing context fails closed with 401 before any SSE response is opened.
        // `namespaceId` is an OPTIONAL filter: absent/blank subscribes to the
        // whole tenant scope (a scoped key, not the shared global `""` bucket).
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId, requireNamespace = false)
        // SSE framing headers: disable intermediary buffering/caching so events
        // reach the client immediately (byte-for-byte parity with the Node hub).
        response.setHeader("Cache-Control", "no-cache, no-transform")
        response.setHeader("Connection", "keep-alive")
        response.setHeader("X-Accel-Buffering", "no")
        return hub.register(caller.scope, caller.namespaceId.ifBlank { null })
    }
}
