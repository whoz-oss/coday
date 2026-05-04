package io.whozoss.agentos.schedule

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
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
 *
 * [create] is overridden to inject the [userId] of the authenticated caller so
 * the scheduler can later fire the message under the correct user identity.
 * The [userId] supplied in the request body (if any) is ignored — it is always
 * resolved server-side from the current HTTP session.
 */
@RestController
@RequestMapping(
    "/api/schedules",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class ScheduleController(
    private val scheduleService: ScheduleService,
    private val userService: UserService,
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
            userId = entity.userId,
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
            userId = resource.userId,
            message = resource.message,
            enabled = resource.enabled,
            oneShot = resource.oneShot,
            triggerAt = resource.triggerAt,
            intervalSchedule = resource.intervalSchedule,
            nextTriggerAt = resource.nextTriggerAt,
            lastTriggeredAt = resource.lastTriggeredAt,
            occurrenceCount = resource.occurrenceCount,
        )

    // -------------------------------------------------------------------------
    // Overrides
    // -------------------------------------------------------------------------

    /**
     * POST /api/schedules — create a new schedule.
     *
     * Overrides [EntityController.create] to inject the current user's id as
     * [Schedule.userId], regardless of what the client supplies in the body.
     * This ensures the scheduler fires messages under the identity of the person
     * who created the schedule.
     */
    @org.springframework.web.bind.annotation.PostMapping(
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(@Valid @RequestBody resource: ScheduleResource): ScheduleResource {
        val currentUserId = userService.getCurrentUser().id
        return toResource(scheduleService.create(toDomain(resource).copy(userId = currentUserId)))
    }

    companion object : KLogging()
}
