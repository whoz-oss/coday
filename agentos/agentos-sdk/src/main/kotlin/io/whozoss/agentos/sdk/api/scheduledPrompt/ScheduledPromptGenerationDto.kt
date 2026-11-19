package io.whozoss.agentos.sdk.api.scheduledPrompt

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
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
 * @param baseFlowConfiguration A flow configuration to start from, `{ search: { tool, params }, act: { agentName,
 *   prompt } }`, every part optional. The generation only enriches it; an explicit request field wins over its
 *   counterpart.
 * @param flowConfigurationPresets Named base flow configurations to choose from, used only when
 *   [baseFlowConfiguration] is absent: the LLM chooses one from [description] (a single preset is chosen as is), and
 *   its configuration then acts exactly like [baseFlowConfiguration]. The chosen name is reported as
 *   [ScheduledPromptGenerationResult.chosenPreset].
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
    @field:Size(
        min = 1,
        max = MAX_DESCRIPTION_LENGTH,
        message = "description must be between 1 and $MAX_DESCRIPTION_LENGTH characters"
    )
    val description: String,
    @field:Size(max = MAX_NAME_LENGTH, message = "loopAgentName must be at most $MAX_NAME_LENGTH characters")
    @field:Schema(types = ["string", "null"], description = "LOOP agent running the automation. Inferred when absent.")
    val loopAgentName: String? = null,
    @field:Size(max = MAX_NAME_LENGTH, message = "targetAgentName must be at most $MAX_NAME_LENGTH characters")
    @field:Schema(
        types = ["string", "null"],
        description = "Agent started for each search result. Inferred when absent."
    )
    val targetAgentName: String? = null,
    @field:Size(max = MAX_PROMPT_LENGTH, message = "prompt must be at most $MAX_PROMPT_LENGTH characters")
    @field:Schema(
        types = ["string", "null"],
        description = "Opening message of each started case. Inferred when absent."
    )
    val prompt: String? = null,
    @field:Size(max = MAX_NAME_LENGTH, message = "searchToolName must be at most $MAX_NAME_LENGTH characters")
    @field:Schema(
        types = ["string", "null"],
        description = "Search tool of the loop agent. Chosen by the LLM when absent."
    )
    val searchToolName: String? = null,
    @field:Schema(
        ref = "#/components/schemas/JsonNode",
        description =
            "Flow configuration to start from, `{ search: { tool, params }, act: { agentName, prompt } }`, every part " +
                "optional. The generation only enriches it: `search.tool` is used as the search tool, `search.params` " +
                "are kept as they are and only completed, `act.agentName` is the target agent and `act.prompt` the " +
                "prompt. An explicit request field wins over its counterpart here.",
    )
    val baseFlowConfiguration: JsonNode? = null,
    @field:Valid
    @field:Size(max = MAX_PRESETS, message = "flowConfigurationPresets must hold at most $MAX_PRESETS presets")
    @field:Schema(
        types = ["array", "null"],
        description =
            "Named base flow configurations, used only when `baseFlowConfiguration` is absent. The LLM chooses one " +
                "from the description (a single preset is chosen as is), and its `baseFlowConfiguration` then acts " +
                "exactly like the request's `baseFlowConfiguration`: an explicit request field still wins over it. " +
                "The chosen name is returned as `chosenPreset`.",
    )
    val flowConfigurationPresets: List<FlowConfigurationPreset>? = null,
    @field:Valid
    val recurrence: RecurrenceDto? = null,
    @field:Valid
    val planning: PlanningDto? = null,
) {
    @get:AssertTrue(message = "Provide exactly one of namespaceId or namespaceExternalId")
    @get:JsonIgnore
    val isNamespaceIdentifierValid: Boolean
        get() = (namespaceId == null) != (namespaceExternalId == null)

    /** A JSON object, bounded like the other free inputs: it is injected into LLM prompts. An explicit null is none. */
    @get:AssertTrue(
        message = "baseFlowConfiguration must be a JSON object of at most $MAX_BASE_FLOW_CONFIGURATION_LENGTH characters",
    )
    @get:JsonIgnore
    val isBaseFlowConfigurationValid: Boolean
        get() =
            baseFlowConfiguration == null ||
                baseFlowConfiguration.isNull ||
                (baseFlowConfiguration.isObject && baseFlowConfiguration.toString().length <= MAX_BASE_FLOW_CONFIGURATION_LENGTH)

    /**
     * No null element, preset names unique as the selector matches them (`equals(ignoreCase = true)`, not a
     * locale-sensitive `lowercase()`), and the presets bounded as a whole: they are injected into an LLM prompt.
     *
     * Bean Validation runs this getter even when an element is itself invalid, so it must not assume more than Jackson
     * guarantees: the Kotlin module rejects a missing `name`, and a JSON null `baseFlowConfiguration` is a `NullNode`,
     * but a null **element** of the list goes through.
     */
    @get:AssertTrue(
        message =
            "flowConfigurationPresets must hold no null element, have unique names (case-insensitive) and weigh at " +
                "most $MAX_PRESETS_TOTAL_LENGTH characters in total",
    )
    @get:JsonIgnore
    val isFlowConfigurationPresetsValid: Boolean
        get() {
            val all = flowConfigurationPresets.orEmpty()
            val presets = all.filterNotNull()
            val names = presets.map { it.name }
            val unique = names.indices.none { i -> (i + 1 until names.size).any { j -> names[i].equals(names[j], ignoreCase = true) } }
            val weight = presets.sumOf { it.name.length + it.baseFlowConfiguration.toString().length }
            return presets.size == all.size && unique && weight <= MAX_PRESETS_TOTAL_LENGTH
        }

    companion object {
        const val MAX_BASE_FLOW_CONFIGURATION_LENGTH = 10_000
        const val MAX_DESCRIPTION_LENGTH = 2000
        const val MAX_PROMPT_LENGTH = 4000
        const val MAX_NAME_LENGTH = 200
        const val MAX_PRESETS = 20
        const val MAX_PRESETS_TOTAL_LENGTH = 30_000
    }
}

/**
 * A named base flow configuration the generation can start from, see
 * [ScheduledPromptGenerationRequest.flowConfigurationPresets].
 *
 * @param name Identifies the preset, and is what the LLM answers with. Unique within a request, case-insensitively.
 * @param baseFlowConfiguration Same shape and rules as [ScheduledPromptGenerationRequest.baseFlowConfiguration].
 */
@Schema(name = "FlowConfigurationPreset")
data class FlowConfigurationPreset(
    @field:NotBlank(message = "name must not be blank")
    @field:Size(max = ScheduledPromptGenerationRequest.MAX_NAME_LENGTH, message = "name must be at most ${ScheduledPromptGenerationRequest.MAX_NAME_LENGTH} characters")
    val name: String,
    @field:Schema(
        ref = "#/components/schemas/JsonNode",
        description =
            "Flow configuration to start from, `{ search: { tool, params }, act: { agentName, prompt } }`, every part " +
                "optional, with the same meaning as the request's `baseFlowConfiguration`.",
    )
    val baseFlowConfiguration: JsonNode,
) {
    /** A JSON object, bounded like the request's `baseFlowConfiguration`: it is injected into an LLM prompt. */
    @get:AssertTrue(
        message =
            "baseFlowConfiguration must be a JSON object of at most " +
                "${ScheduledPromptGenerationRequest.MAX_BASE_FLOW_CONFIGURATION_LENGTH} characters",
    )
    @get:JsonIgnore
    val isBaseFlowConfigurationValid: Boolean
        get() =
            baseFlowConfiguration.isObject &&
                baseFlowConfiguration.toString().length <= ScheduledPromptGenerationRequest.MAX_BASE_FLOW_CONFIGURATION_LENGTH
}

/** A field of a generated scheduled prompt, as reported in [GeneratedFieldDecision]. */
enum class GeneratedField { LOOP_AGENT, TARGET_AGENT, PROMPT, NAME, RECURRENCE, PLANNING, SEARCH, PRESET }

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
 * [summary] tells a non-technical end user, in a few lines of **Markdown** and in their preferred language, what the
 * proposal does: a bold schedule and the agent's name on the opening line, then `- ` bullets for what is selected each
 * time and what is done for each item. It is written from the final values of [scheduledPrompt], and is null when
 * there is no proposal or when it could not be written (a warning then says so). [decisions] is its technical
 * counterpart.
 *
 * [chosenPreset] is the name of the flow configuration preset the generation started from, see
 * [ScheduledPromptGenerationRequest.flowConfigurationPresets]; it is returned whether or not there is a proposal.
 *
 * [rawResponse] carries the raw LLM text, only when an LLM answer could not be used.
 */
@Schema(name = "ScheduledPromptGenerationResult")
data class ScheduledPromptGenerationResult(
    @field:Schema(description = "The proposal, absent (null) when a part of it could not be determined: see `warnings`.")
    val scheduledPrompt: ScheduledPromptDto?,
    @field:Schema(
        types = ["string", "null"],
        description =
            "Markdown summary of the proposal for a non-technical end user, in their preferred language: a bold " +
                    "schedule and the agent's name on the opening line, then `- ` bullets for what is selected each time " +
                    "and what is done for each item. Render it as Markdown. Null when there is no proposal or when it " +
                    "could not be written.",
    )
    val summary: String? = null,
    val decisions: List<GeneratedFieldDecision> = emptyList(),
    val warnings: List<String> = emptyList(),
    @field:Schema(
        types = ["string", "null"],
        description =
            "Name of the flow configuration preset the generation started from. Null when `baseFlowConfiguration` " +
                "was given, no presets were given, or no preset could be chosen (see `warnings`). Returned whether or " +
                "not there is a proposal.",
    )
    val chosenPreset: String? = null,
    @field:Schema(types = ["string", "null"])
    val rawResponse: String? = null,
)
