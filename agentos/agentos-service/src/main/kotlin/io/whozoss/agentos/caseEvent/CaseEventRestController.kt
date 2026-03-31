@file:Suppress("ktlint:standard:filename")

package io.whozoss.agentos.caseEvent

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Read-only REST API for [CaseEvent] entities.
 *
 * Case events are an immutable audit log produced exclusively by the runtime.
 * All mutations go through [io.whozoss.agentos.caseFlow.CaseService].
 * The SSE streaming endpoint lives in [CaseEventSseController].
 *
 * All endpoints explicitly reference the named `CaseEvent` schema (via
 * `@Schema(implementation = CaseEvent::class)`) to prevent springdoc from emitting
 * an anonymous inline `oneOf` in endpoint responses, which causes the OpenAPI
 * generator to produce cryptic filenames like `get-by-ids3200-response-inner`
 * in the Angular client (GitHub issue #695).
 */
@RestController
@RequestMapping("/api/case-events", produces = [MediaType.APPLICATION_JSON_VALUE])
class CaseEventRestController(
    private val caseEventService: CaseEventService,
) {
    /** GET /api/case-events/{id} — get a single event by ID. */
    @Operation(
        summary = "Get a single case event by ID",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(schema = Schema(implementation = CaseEvent::class))],
            ),
        ],
    )
    @GetMapping("/{id}")
    fun getById(
        @PathVariable id: UUID,
    ): CaseEvent =
        caseEventService.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "CaseEvent not found: $id")

    /** POST /api/case-events/by-ids — get multiple events by IDs. */
    @Operation(
        summary = "Get multiple case events by IDs",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        array = ArraySchema(schema = Schema(implementation = CaseEvent::class)),
                    ),
                ],
            ),
        ],
    )
    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun getByIds(
        @RequestBody ids: List<UUID>,
    ): List<CaseEvent> = caseEventService.findByIds(ids)

    /** GET /api/case-events/by-parentId/{caseId} — list all events for a case, ordered by timestamp. */
    @Operation(
        summary = "List all events for a case, ordered by timestamp",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        array = ArraySchema(schema = Schema(implementation = CaseEvent::class)),
                    ),
                ],
            ),
        ],
    )
    @GetMapping("/by-parentId/{caseId}")
    fun listByCase(
        @PathVariable caseId: UUID,
    ): List<CaseEvent> = caseEventService.findByParent(caseId)
}
