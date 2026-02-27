@file:Suppress("ktlint:standard:filename")

package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for reading and managing CaseEvent entities.
 *
 * Extends EntityController for standard CRUD operations.
 * CaseEvents are append-only by intent, but all operations remain available
 * pending proper access control.
 *
 * The SSE streaming endpoint lives in CaseEventSseController (tagged "sse", excluded from OpenAPI).
 *
 * Endpoints (inherited):
 * - GET    /api/case-events/{id}          — get a single event
 * - GET    /api/case-events?ids=a,b,c     — get specific events by IDs
 * - GET    /api/case-events?parentId=xxx  — list all events for a case (ordered by timestamp)
 * - POST   /api/case-events               — create an event
 * - PUT    /api/case-events/{id}          — update an event
 * - DELETE /api/case-events/{id}          — soft-delete an event
 */
@RestController
@RequestMapping("/api/case-events")
class CaseEventRestController(
    service: CaseEventService,
) : EntityController<CaseEvent, UUID>(service)
