package io.whozoss.agentos.schedule

import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.sdk.schedule.IntervalSchedule
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

/**
 * HTTP resource (DTO) for [Schedule] entities.
 *
 * Annotated with @Schema(name = "Schedule") so that the generated OpenAPI spec
 * keeps the schema name "Schedule" instead of "ScheduleResource".
 *
 * [namespaceId] and [message] are required.
 * All other fields are optional — validation of temporal strategy coherence
 * (triggerAt XOR intervalSchedule) is deferred to the scheduler layer.
 */
@Schema(name = "Schedule")
data class ScheduleResource(
    val id: UUID? = null,
    @field:NotNull(message = "namespaceId must not be null")
    val namespaceId: UUID,
    val caseId: UUID? = null,
    val agentName: String? = null,
    val userId: UUID? = null,
    @field:NotBlank(message = "message must not be blank")
    val message: String,
    val enabled: Boolean = true,
    val oneShot: Boolean = false,
    val triggerAt: Instant? = null,
    val intervalSchedule: IntervalSchedule? = null,
    val nextTriggerAt: Instant? = null,
    val lastTriggeredAt: Instant? = null,
    val occurrenceCount: Int = 0,
)
