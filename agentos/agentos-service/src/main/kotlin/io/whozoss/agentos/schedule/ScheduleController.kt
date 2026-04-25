package io.whozoss.agentos.schedule

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for managing [Schedule] entities.
 *
 * Extends [EntityController] with [ScheduleResource] as the HTTP DTO.
 *
 * Standard CRUD endpoints (inherited):
 *   GET    /api/schedules/{id}
 *   POST   /api/schedules/by-ids
 *   GET    /api/schedules/by-parentId/{parentId}   ← lists by namespaceId
 *   POST   /api/schedules
 *   PUT    /api/schedules/{id}
 *   DELETE /api/schedules/{id}
 */
@RestController
@RequestMapping(
    "/api/schedules",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class ScheduleController(
    private val scheduleService: ScheduleService,
) : EntityController<Schedule, UUID, ScheduleResource>(scheduleService) {

    // -------------------------------------------------------------------------
    // Mapping between domain entity and HTTP resource
    // -------------------------------------------------------------------------

    override fun toResource(entity: Schedule): ScheduleResource =
        ScheduleResource(
            id = entity.metadata.id,
            namespaceId = entity.namespaceId,
            caseId = entity.caseId,
            agentName = entity.agentName,
            message = entity.message,
            enabled = entity.enabled,
            oneShot = entity.oneShot,
            triggerAt = entity.triggerAt,
            intervalSchedule = entity.intervalSchedule,
            nextTriggerAt = entity.nextTriggerAt,
            lastTriggeredAt = entity.lastTriggeredAt,
            occurrenceCount = entity.occurrenceCount,
        )

    override fun toDomain(resource: ScheduleResource): Schedule =
        Schedule(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            namespaceId = resource.namespaceId,
            caseId = resource.caseId,
            agentName = resource.agentName,
            message = resource.message,
            enabled = resource.enabled,
            oneShot = resource.oneShot,
            triggerAt = resource.triggerAt,
            intervalSchedule = resource.intervalSchedule,
            nextTriggerAt = resource.nextTriggerAt,
            lastTriggeredAt = resource.lastTriggeredAt,
            occurrenceCount = resource.occurrenceCount,
        )

    companion object : KLogging()
}
