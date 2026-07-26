package io.whozoss.agentos.sdk.api.caseDefinition

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

// ---------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------

/** Recurrence unit. */
enum class SchedulerUnit { DAY, WEEK, MONTH }

/** Controls when a scheduled case definition stops executing. */
enum class SchedulerEndType {
    /** Runs indefinitely. */
    NEVER,
    /** Stops on [PlanningDto.endDate] (inclusive). Must be after [PlanningDto.startDate]. */
    ON_DATE,
    /** Stops after [PlanningDto.occurrenceCount] executions. */
    OCCURRENCES,
}

// ---------------------------------------------------------------------------
// Nested DTOs
// ---------------------------------------------------------------------------

/**
 * Recurrence schedule: how often and at what time the case fires.
 *
 * [every] + [unit] define the interval (e.g. every 2 weeks).
 * [days] is an optional filter on days of the week (Java [DayOfWeek]);
 * an empty list means no day filter.
 * [timeUtc] is the execution time in UTC, serialised as "HH:mm" by Jackson.
 */
@Schema(name = "Recurrence")
data class RecurrenceDto(
    @field:NotNull(message = "every must not be null")
    @field:Positive(message = "every must be > 0")
    val every: Int,
    @field:NotNull(message = "unit must not be null")
    val unit: SchedulerUnit,
    val days: List<DayOfWeek> = emptyList(),
    @field:NotNull(message = "timeUtc must not be null")
    @field:JsonFormat(pattern = "HH:mm")
    val timeUtc: LocalTime,
)

/**
 * Planning window: when the schedule starts and when (if ever) it ends.
 *
 * [startDate] is the earliest date on which a firing may occur.
 * [endType] controls termination:
 * - NEVER: runs indefinitely.
 * - ON_DATE: stops after [endDate] (required, must be strictly after [startDate]).
 * - OCCURRENCES: stops after [occurrenceCount] executions (required, > 0).
 */
@Schema(name = "Planning")
data class PlanningDto(
    @field:NotNull(message = "startDate must not be null")
    val startDate: LocalDate,
    @field:NotNull(message = "endType must not be null")
    val endType: SchedulerEndType,
    val endDate: LocalDate? = null,
    @field:Positive(message = "occurrenceCount must be > 0")
    val occurrenceCount: Int? = null,
)

// ---------------------------------------------------------------------------
// Root DTO
// ---------------------------------------------------------------------------

/**
 * HTTP DTO for [io.whozoss.agentos.caseDefinition.CaseDefinition] entities.
 *
 * Scope is inferred from `(namespaceId, userId)` on POST:
 * - (null, null)  → platform (Super Admin only)
 * - (ns, null)    → namespace-scoped (WRITE on namespace)
 * - (null, me)    → user-global (authenticated only)
 * - (ns, me)      → user × namespace (READ on namespace)
 *
 * On PUT, [namespaceId], [userId] and [agentConfigId] are immutable.
 *
 * [promptContent] is the opening message sent to the agent; the backend manages
 * the linked Prompt entity automatically.
 *
 * [recurrence] describes how often and at what time the case fires.
 * [planning] describes the start date and end condition.
 *
 * Cross-field constraints (endDate after startDate, occurrenceCount > 0) are
 * validated in the controller before delegating to the service.
 */
@Schema(name = "CaseDefinition")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CaseDefinitionDto(
    val id: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val namespaceId: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val userId: UUID? = null,
    @field:NotNull(message = "agentConfigId must not be null")
    val agentConfigId: UUID,
    @field:NotBlank(message = "promptContent must not be blank")
    val promptContent: String,
    @field:NotBlank(message = "name must not be blank")
    val name: String,
    val description: String? = null,
    @field:NotNull(message = "recurrence must not be null")
    @field:Valid
    val recurrence: RecurrenceDto,
    @field:NotNull(message = "planning must not be null")
    @field:Valid
    val planning: PlanningDto,
    val enabled: Boolean = true,
    val createdBy: String? = null,
    val createdOn: Instant? = null,
    val updatedBy: String? = null,
    val updatedOn: Instant? = null,
)
