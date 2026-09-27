package io.whozoss.factory.runs.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.forge.web.resolveForgeCaller
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.runs.service.LegacyRunService
import io.whozoss.factory.web.TrustContext
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * SSE stream of a legacy run's live stdout.
 *
 * Port of the `GET /api/runs/:id/stream` and
 * `GET /api/factory/runs/:id/stream` routes in `factory/dashboard/run-routes.mjs`.
 * The routes are `@Operation(hidden = true)` so they are excluded from the
 * generated OpenAPI client. A failure while opening the stream (e.g. missing
 * trust context) is rendered as an `application/json` error envelope by the
 * shared `FactoryExceptionHandler`.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "sse", description = "Server-Sent Events streams")
class LegacyRunSseController(
    private val runs: LegacyRunService,
    private val tenantScopeProvider: TenantScopeProvider,
) {

    @GetMapping(path = ["/runs/{id}/stream", "/factory/runs/{id}/stream"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(hidden = true)
    fun stream(
        @PathVariable id: String,
        @Parameter(hidden = true) trustContext: TrustContext?,
        response: HttpServletResponse,
    ): SseEmitter {
        // Identity is resolved before any SSE response is opened; a missing
        // context fails closed with 401 and a JSON error envelope.
        resolveForgeCaller(trustContext, tenantScopeProvider)
        response.setHeader("Cache-Control", "no-cache")
        response.setHeader("Connection", "keep-alive")
        response.setHeader("Access-Control-Allow-Origin", "*")
        return runs.attachSseStream(id)
    }
}
