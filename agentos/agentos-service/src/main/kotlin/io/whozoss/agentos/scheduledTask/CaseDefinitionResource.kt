package io.whozoss.agentos.scheduledTask

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.time.Instant
import java.util.UUID

/**
 * HTTP DTO for [CaseDefinition] entities.
 *
 * ### Namespace query param vs. namespaceId in body
 *
 * The `?namespaceId=` query parameter on every endpoint serves routing and security
 * (namespace-level permission check). The controller validates that [namespaceId] in the
 * body matches it.
 *
 * ### Targeting fields
 *
 * [namespaceId] is always required. [userGroupId] and [userId] are optional refinements.
 * They are mutually exclusive — setting both produces a 400 via [isValidCombination].
 *
 * | namespaceId | userGroupId | userId | Valid |
 * |-------------|-------------|--------|-------|
 * | set         | null        | null   | ✓     |
 * | set         | set         | null   | ✓     |
 * | set         | null        | set    | ✓     |
 * | set         | set         | set    | ✗ 400 |
 *
 * Bean Validation rules:
 * - [name] must not be blank
 * - [namespaceId], [agentId], [prompt], [schedule] must not be null
 * - [description] is optional
 */
@Schema(name = "CaseDefinition")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CaseDefinitionResource(
    val id: UUID? = null,
    @field:NotNull(message = "namespaceId must not be null")
    val namespaceId: UUID,
    val userGroupId: UUID? = null,
    val userId: UUID? = null,
    @field:NotBlank(message = "name must not be blank")
    val name: String,
    val description: String? = null,
    @field:NotNull(message = "agentId must not be null")
    val agentId: UUID,
    @field:NotBlank(message = "prompt must not be blank")
    val prompt: String,
    @field:NotNull(message = "schedule must not be null")
    @field:Valid
    val schedule: TaskScheduleResource,
    val enabled: Boolean = true,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    /**
     * Validates that [userGroupId] and [userId] are not both set.
     * Called by Bean Validation via `@AssertTrue`.
     */
    @AssertTrue(message = "userGroupId and userId cannot both be set")
    fun isValidCombination(): Boolean = userGroupId == null || userId == null
}

/**
 * HTTP DTO for the schedule part of a [CaseDefinition].
 *
 * The cron expression is an internal persistence detail and is **never** included here.
 * [CronExpressionConverter] handles the bidirectional conversion in the controller.
 *
 * - [frequency]: [ScheduleFrequency.DAILY] or [ScheduleFrequency.WEEKLY].
 * - [timeUtc]: UTC time in `HH:mm` format.
 * - [dayOfWeek]: Required for WEEKLY. One of `MON TUE WED THU FRI SAT SUN`.
 */
@Schema(name = "TaskSchedule")
data class TaskScheduleResource(
    @field:NotNull(message = "schedule.frequency must not be null")
    val frequency: ScheduleFrequency,
    @field:NotBlank(message = "schedule.timeUtc must not be blank")
    @field:Pattern(
        regexp = "^([01]\\d|2[0-3]):[0-5]\\d$",
        message = "schedule.timeUtc must be in HH:mm format (e.g. \"09:00\")",
    )
    val timeUtc: String,
    val dayOfWeek: String? = null,
)

/** Recurrence frequency. Only [DAILY] and [WEEKLY] are supported in step 1. */
enum class ScheduleFrequency {
    DAILY,
    WEEKLY,
}
