package io.whozoss.agentos.sdk.api.agentConfig

import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

/**
 * Request body for the loop-config generation endpoint.
 *
 * The caller supplies a free-text [description] of what the search should find and who
 * should be targeted.
 *
 * When [searchToolName] is provided, the endpoint uses that specific tool's schema to
 * constrain the LLM output.
 *
 * When [searchToolName] is omitted, the LLM receives all available tools and their schemas
 * and selects the most appropriate one itself, returning both the chosen tool name and the
 * generated params.
 *
 * @param description     Natural-language description of the desired search parameters.
 *   Example: "I want to target the owners of the tasks starting this week"
 * @param searchToolName  Optional. Name of the search tool to use. When null, the LLM
 *   picks the best tool from all tools available to the agent.
 */
@Schema(name = "LoopConfigGenerationRequest")
data class LoopConfigGenerationRequest(
    @field:NotBlank(message = "description must not be blank")
    val description: String,
    @field:Schema(
        description =
            "Name of the search tool to use. When omitted, the LLM picks the best tool " +
                "from all tools available to this agent.",
        nullable = true,
    )
    val searchToolName: String? = null,
)

/**
 * Response body for the loop-config generation endpoint.
 *
 * [loopConfig] is the complete, ready-to-paste value for [AgentConfigDto.loopConfig]:
 * ```json
 * {
 *   "search": {
 *     "tool": "SearchTasks",
 *     "params": { "startDatePeriod": ["THIS_WEEK"], "resolveTargets": ["OWNER"] }
 *   }
 * }
 * ```
 * It is null when generation failed (see [rawResponse] and [warning]).
 *
 * [rawResponse] carries the raw LLM text for debugging when the structured
 * parse fails — it is null when [loopConfig] was produced successfully.
 *
 * [warning] is set when the LLM output required a fallback parse or when
 * the generation succeeded but produced an empty params object.
 */
@Schema(name = "LoopConfigGenerationResult")
data class LoopConfigGenerationResult(
    @field:Schema(
        description =
            "Complete loopConfig value ready to set on an AgentConfig. " +
                "Contains `search.tool` and `search.params`. Null when generation failed.",
        type = "object",
        nullable = true,
    )
    val loopConfig: JsonNode?,
    @field:Schema(
        description = "Raw LLM response text, present only when structured parsing failed.",
        nullable = true,
    )
    val rawResponse: String? = null,
    @field:Schema(
        description = "Non-fatal warning (e.g. empty object generated, or fallback parse used).",
        nullable = true,
    )
    val warning: String? = null,
)
