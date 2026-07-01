package io.whozoss.agentos.caseDefinition

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
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
 * ### Schedule fields (flat)
 *
 * [frequency], [timeUtc], and [dayOfWeek] are flat on this DTO (no nested object).
 * - [frequency]: required.
 * - [timeUtc]: required, `HH:mm` format.
 * - [dayOfWeek]: required when [frequency] is [ScheduleFrequency.WEEKLY]. One of
 *   `MON TUE WED THU FRI SAT SUN`.
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
    @field:NotNull(message = "frequency must not be null")
    val frequency: ScheduleFrequency,
    @field:NotBlank(message = "timeUtc must not be blank")
    @field:Pattern(
        regexp = "^([01]\\d|2[0-3]):[0-5]\\d$",
        message = "timeUtc must be in HH:mm format (e.g. \"09:00\")",
    )
    val timeUtc: String,
    /** Required when [frequency] is [ScheduleFrequency.WEEKLY]. One of `MON TUE WED THU FRI SAT SUN`. */
    val dayOfWeek: String? = null,
    val enabled: Boolean = true,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    @AssertTrue(message = "userGroupId and userId cannot both be set")
    fun isValidCombination(): Boolean = userGroupId == null || userId == null
}

/** Recurrence frequency. Only [DAILY] and [WEEKLY] are supported in step 1. */
enum class ScheduleFrequency {
    DAILY,
    WEEKLY,
}
