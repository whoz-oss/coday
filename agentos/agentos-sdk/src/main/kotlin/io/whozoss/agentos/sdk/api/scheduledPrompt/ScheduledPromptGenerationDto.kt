package io.whozoss.agentos.sdk.api.scheduledPrompt

import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * Request body for `POST /api/scheduled-prompts/generate`.
 *
 * Only [description] is required. Every other field is optional: a field that is provided is kept as is, a field
 * that is absent is decided by an LLM from the description and from the agents the caller can access.
 *
 * Free-text fields are bounded: they are injected verbatim into LLM prompts, and an oversized one must be a 400,
 * not a provider error.
 *
 * **Namespace resolution:** provide exactly one of [namespaceId] or [namespaceExternalId], like
 * [ScheduledPromptEffectiveRequest].
 *
 * @param description Natural-language description of the automation, e.g. "every Monday at 9:00, ask the owners of
 *   the tasks starting this week to update them".
 * @param loopAgentName The LOOP agent that runs the automation (the agent of the scheduled prompt).
 * @param targetAgentName The agent started for each search result (`flowConfiguration.act.agentName`).
 * @param prompt The opening message of each started case: the scheduled prompt's `promptContent`, which the scheduler
 *   sets as `act.prompt` at run time.
 * @param searchToolName The search tool of the loop agent to use. When absent, the LLM picks one among its tools.
 * @param recurrence How often and at what time the scheduled prompt fires.
 * @param planning When the schedule starts and ends.
 */
@Schema(name = "ScheduledPromptGenerationRequest")
data class ScheduledPromptGenerationRequest(
    @field:Schema(types = ["string", "null"], format = "uuid")
    val namespaceId: UUID? = null,
    @field:Schema(types = ["string", "null"])
    val namespaceExternalId: String? = null,
    @field:NotBlank(message = "description must not be blank")
    @field:Size(min = 1, max = MAX_DESCRIPTION_LENGTH, message = "description must be between 1 and $MAX_DESCRIPTION_LENGTH characters")
    val description: String,
    @field:Size(max = MAX_NAME_LENGTH, message = "loopAgentName must be at most $MAX_NAME_LENGTH characters")
    @field:Schema(types = ["string", "null"], description = "LOOP agent running the automation. Inferred when absent.")
    val loopAgentName: String? = null,
    @field:Size(max = MAX_NAME_LENGTH, message = "targetAgentName must be at most $MAX_NAME_LENGTH characters")
    @field:Schema(types = ["string", "null"], description = "Agent started for each search result. Inferred when absent.")
    val targetAgentName: String? = null,
    @field:Size(max = MAX_PROMPT_LENGTH, message = "prompt must be at most $MAX_PROMPT_LENGTH characters")
    @field:Schema(types = ["string", "null"], description = "Opening message of each started case. Inferred when absent.")
    val prompt: String? = null,
    @field:Size(max = MAX_NAME_LENGTH, message = "searchToolName must be at most $MAX_NAME_LENGTH characters")
    @field:Schema(types = ["string", "null"], description = "Search tool of the loop agent. Chosen by the LLM when absent.")
    val searchToolName: String? = null,
    @field:Valid
    val recurrence: RecurrenceDto? = null,
    @field:Valid
    val planning: PlanningDto? = null,
) {
    @get:AssertTrue(message = "Provide exactly one of namespaceId or namespaceExternalId")
    @get:JsonIgnore
    val isNamespaceIdentifierValid: Boolean
        get() = (namespaceId == null) != (namespaceExternalId == null)

    companion object {
        const val MAX_DESCRIPTION_LENGTH = 2000
        const val MAX_PROMPT_LENGTH = 4000
        const val MAX_NAME_LENGTH = 200
    }
}

/** A field of a generated scheduled prompt, as reported in [GeneratedFieldDecision]. */
enum class GeneratedField { LOOP_AGENT, TARGET_AGENT, PROMPT, NAME, RECURRENCE, PLANNING, SEARCH }

/** Whether a field of a generated scheduled prompt came from the request or was decided by the generation. */
enum class GeneratedFieldSource { PROVIDED, INFERRED }

/**
 * How one field of the proposal was obtained.
 *
 * @param value A short, human-readable rendering of the value (an agent name, a tool name...), when meaningful.
 * @param rationale The LLM's short justification, when [source] is [GeneratedFieldSource.INFERRED].
 */
@Schema(name = "GeneratedFieldDecision")
data class GeneratedFieldDecision(
    val field: GeneratedField,
    val source: GeneratedFieldSource,
    @field:Schema(types = ["string", "null"])
    val value: String? = null,
    @field:Schema(types = ["string", "null"])
    val rationale: String? = null,
)

/**
 * Response body for `POST /api/scheduled-prompts/generate`: a **proposal**, nothing is persisted.
 *
 * [scheduledPrompt] passed Bean Validation as a whole and can be POSTed to `POST /api/scheduled-prompts` once a human
 * has reviewed it. It is proposed **disabled** (`enabled = false`): posting it unchanged creates the schedule without
 * starting it, and enabling it is a separate, deliberate step. Its `agentConfigId` is the loop agent, and its
 * `flowConfiguration` is `{ "search": { tool, params }, "act": { agentName } }`, without `act.prompt`: the scheduler
 * sets it from `promptContent` at run time.
 *
 * [scheduledPrompt] is null when a part of it could not be determined or the whole is invalid; [warnings] then say
 * why. [warnings] may also flag caveats on a non-null proposal.
 *
 * [rawResponse] carries the raw LLM text, only when an LLM answer could not be used.
 */
@Schema(name = "ScheduledPromptGenerationResult")
data class ScheduledPromptGenerationResult(
    @field:Schema(description = "The proposal, absent (null) when a part of it could not be determined: see `warnings`.")
    val scheduledPrompt: ScheduledPromptDto?,
    val decisions: List<GeneratedFieldDecision> = emptyList(),
    val warnings: List<String> = emptyList(),
    @field:Schema(types = ["string", "null"])
    val rawResponse: String? = null,
)
