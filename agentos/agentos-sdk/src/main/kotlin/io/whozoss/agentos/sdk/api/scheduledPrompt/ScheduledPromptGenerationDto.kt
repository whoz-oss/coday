package io.whozoss.agentos.sdk.api.scheduledPrompt

import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import java.util.UUID

/**
 * Request body for `POST /api/scheduled-prompts/generate`.
 *
 * Only [description] is required. Every other field is optional: a field that is provided is kept as is, a field
 * that is absent is decided by an LLM from the description and from the agents the caller can access.
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
    val description: String,
    @field:Schema(description = "LOOP agent running the automation. Inferred when absent.", nullable = true)
    val loopAgentName: String? = null,
    @field:Schema(description = "Agent started for each search result. Inferred when absent.", nullable = true)
    val targetAgentName: String? = null,
    @field:Schema(description = "Opening message of each started case. Inferred when absent.", nullable = true)
    val prompt: String? = null,
    @field:Schema(description = "Search tool of the loop agent. Chosen by the LLM when absent.", nullable = true)
    val searchToolName: String? = null,
    @field:Valid
    @field:Schema(nullable = true)
    val recurrence: RecurrenceDto? = null,
    @field:Valid
    @field:Schema(nullable = true)
    val planning: PlanningDto? = null,
) {
    @get:AssertTrue(message = "Provide exactly one of namespaceId or namespaceExternalId")
    @get:JsonIgnore
    val isNamespaceIdentifierValid: Boolean
        get() = (namespaceId == null) != (namespaceExternalId == null)
}

/** Whether a field of a generated scheduled prompt came from the request or was decided by the generation. */
enum class GeneratedFieldSource { PROVIDED, INFERRED }

/**
 * How one field of the proposal was obtained.
 *
 * @param field One of `loopAgent`, `targetAgent`, `prompt`, `name`, `recurrence`, `planning`, `search`.
 * @param value A short, human-readable rendering of the value (an agent name, a tool name...), when meaningful.
 * @param rationale The LLM's short justification, when [source] is [GeneratedFieldSource.INFERRED].
 */
@Schema(name = "GeneratedFieldDecision")
data class GeneratedFieldDecision(
    val field: String,
    val source: GeneratedFieldSource,
    @field:Schema(nullable = true)
    val value: String? = null,
    @field:Schema(nullable = true)
    val rationale: String? = null,
)

/**
 * Response body for `POST /api/scheduled-prompts/generate`: a **proposal**, nothing is persisted.
 *
 * [scheduledPrompt] can be POSTed as is to `POST /api/scheduled-prompts` once a human has reviewed it. Its
 * `agentConfigId` is the loop agent, and its `flowConfiguration` is `{ "search": { tool, params }, "act": { agentName } }`,
 * without `act.prompt`: the scheduler sets it from `promptContent` at run time.
 *
 * [scheduledPrompt] is null when a part of it could not be determined; [warnings] then say why. [warnings] may also
 * flag caveats on a non-null proposal.
 *
 * [rawResponse] carries the raw LLM text, only when an LLM answer could not be used.
 */
@Schema(name = "ScheduledPromptGenerationResult")
data class ScheduledPromptGenerationResult(
    @field:Schema(nullable = true)
    val scheduledPrompt: ScheduledPromptDto?,
    val decisions: List<GeneratedFieldDecision> = emptyList(),
    val warnings: List<String> = emptyList(),
    @field:Schema(nullable = true)
    val rawResponse: String? = null,
)
