package io.whozoss.agentos.workflow

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode

/** Kind of a step of a loop described by [LoopSteps]. V1 knows exactly these two, in this order. */
enum class LoopStepType {
    /** Calls a search tool; its hits are what the loop iterates over. */
    SEARCH,

    /** Starts one case per search hit, through the `FreeCaseStarterForUser` tool. */
    START_CASES,
}

/**
 * One step of the `steps` format of an agent's `loopConfig`. `tool`, `args` and `scheduledPromptId` may hold
 * `${loopData...}` placeholders (see [LoopPlaceholders]); `id` and `type` never do.
 *
 * @param id                Name of the step, used in error messages. Distinct and non-blank within a loop.
 * @param tool              Name of the tool the step calls, as granted to the agent (bare or `CONFIG__Tool`).
 * @param args              Input of the tool. For [LoopStepType.SEARCH] it is passed verbatim to the tool; for
 *                          [LoopStepType.START_CASES] it names the agent and the prompt of the cases to start.
 * @param scheduledPromptId [LoopStepType.START_CASES] only: the scheduled prompt to link each started case to.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LoopStepConfig(
    val id: String,
    val type: LoopStepType,
    val tool: String,
    val args: JsonNode? = null,
    val scheduledPromptId: String? = null,
)

/**
 * The `steps` format of an agent's `loopConfig`: the *process* of a loop, whose initial data (`loopData`) comes
 * with the message that triggers the run. See [LoopStepsTranslator] for the rules it must follow.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LoopSteps(
    val steps: List<LoopStepConfig>,
)
