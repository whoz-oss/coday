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
 * Port of the Stage 4 SSE endpoint from `factory/WORKFLOW_PROJECTION.md` and
 * `factory/dashboard/workflow-projection-sse.mjs`:
 *
 * ```http
 * GET /api/factory/workflows/stream?namespaceId=<uuid>
 * Accept: text/event-stream
 * ```
 *
 * The route is `@Operation(hidden = true)` so it is excluded from the generated
 * OpenAPI client (an infinite event stream is not a request/response operation).
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
        // Identity is resolved from the verified trust context; a missing context
        // fails closed with 401 before any SSE response is opened. `namespaceId`
        // is an OPTIONAL filter: absent/blank subscribes to the whole tenant
        // scope (registered under `""`) instead of failing with
        // INVALID_NAMESPACE_ID.
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId, requireNamespace = false)
        // SSE framing headers: disable intermediary buffering/caching so events
        // reach the client immediately (byte-for-byte parity with the Node hub).
        response.setHeader("Cache-Control", "no-cache, no-transform")
        response.setHeader("Connection", "keep-alive")
        response.setHeader("X-Accel-Buffering", "no")
        return hub.register(caller.namespaceId)
    }
}
