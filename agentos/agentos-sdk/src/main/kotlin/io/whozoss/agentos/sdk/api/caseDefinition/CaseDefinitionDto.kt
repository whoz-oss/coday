package io.whozoss.agentos.sdk.api.caseDefinition

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.time.Instant
import java.util.UUID

/**
 * HTTP DTO for [io.whozoss.agentos.caseDefinition.CaseDefinition] entities.
 *
 * [namespaceId] is null for platform-level case definitions and non-null for namespace-scoped ones.
 *
 * On POST, scope is inferred from [namespaceId] and [userId]:
 * - (null, null)  → platform (Super Admin only)
 * - (ns, null)    → namespace-scoped (WRITE on namespace)
 * - (null, me)    → user-global (authenticated only)
 * - (ns, me)      → user × namespace (READ on namespace)
 *
 * On PUT, [namespaceId], [userId] and [agentConfigId] are immutable
 * (preserved from the persisted entity).
 *
 * [agentConfigId] links this case definition to an
 * [io.whozoss.agentos.agentConfig.AgentConfig]. Always required — a case definition
 * always targets a specific agent.
 *
 * [promptContent] is the content of the prompt that will be sent to the agent when the
 * scheduled case fires. The backend creates and manages the associated Prompt entity
 * automatically — the prompt ID is an internal implementation detail not exposed in this DTO.
 *
 * [frequency], [timeUtc] and [dayOfWeek] are the human-readable schedule representation.
 * The backend converts them to/from a 5-field cron expression for storage.
 *
 * [createdBy], [createdOn], [updatedBy], [updatedOn] are read-only audit fields
 * present in GET responses; ignored on write.
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
    @field:NotNull(message = "frequency must not be null")
    val frequency: CaseDefinitionScheduleFrequency,
    @field:NotBlank(message = "timeUtc must not be blank")
    @field:Pattern(
        regexp = "^([01]\\d|2[0-3]):[0-5]\\d$",
        message = "timeUtc must be in HH:mm format (e.g. \"09:00\")",
    )
    val timeUtc: String,
    /** Required when [frequency] is [CaseDefinitionScheduleFrequency.WEEKLY]. One of `MON TUE WED THU FRI SAT SUN`. */
    val dayOfWeek: String? = null,
    val enabled: Boolean = true,
    val createdBy: String? = null,
    val createdOn: Instant? = null,
    val updatedBy: String? = null,
    val updatedOn: Instant? = null,
)

/** Recurrence frequency for a [CaseDefinitionDto]. Only [DAILY] and [WEEKLY] are supported. */
enum class CaseDefinitionScheduleFrequency {
    DAILY,
    WEEKLY,
}
