package io.whozoss.agentos.sdk.api.agentConfig

import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

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
 * @param description     Natural-language description of the desired search parameters, bounded like
 *   `ScheduledPromptGenerationRequest.description`: it is injected verbatim into the LLM prompt.
 *   Example: "I want to target the owners of the tasks starting this week"
 * @param searchToolName  Optional. Name of the search tool to use, bare or prefixed by its integration config. When
 *   null, the LLM picks the best tool from the search tools available to the agent.
 */
@Schema(name = "LoopConfigGenerationRequest")
data class LoopConfigGenerationRequest(
    @field:NotBlank(message = "description must not be blank")
    @field:Size(max = MAX_DESCRIPTION_LENGTH, message = "description must be at most $MAX_DESCRIPTION_LENGTH characters")
    val description: String,
    @field:Size(max = MAX_TOOL_NAME_LENGTH, message = "searchToolName must be at most $MAX_TOOL_NAME_LENGTH characters")
    @field:Schema(
        description =
            "Name of the search tool to use, bare or prefixed by its integration config. When omitted, the LLM " +
                "picks the best tool from the search tools available to this agent.",
        nullable = true,
    )
    val searchToolName: String? = null,
) {
    companion object {
        const val MAX_DESCRIPTION_LENGTH = 2000
        const val MAX_TOOL_NAME_LENGTH = 200
    }
}

/**
 * Response body for the loop-config generation endpoint.
 *
 * [loopConfig] holds the generated **search block** only:
 * ```json
 * {
 *   "search": {
 *     "tool": "SearchTasks",
 *     "params": { "startDatePeriod": ["THIS_WEEK"], "resolveTargets": ["OWNER"] }
 *   }
 * }
 * ```
 * It is **not** a complete loop configuration: the `act` block (the agent started for each search result, and its
 * prompt) is missing, and must be added before it is set as [AgentConfigDto.loopConfig] or as the
 * `flowConfiguration` of a scheduled prompt. It is null when generation failed; [warning] then says why.
 *
 * `search.tool` is the **real** name of the tool on the agent, which may differ from the requested `searchToolName`:
 * a bare name resolves to the tool behind its integration prefix (`searchAgentUsers` gives
 * `USER_GROUP__searchAgentUsers`), the way the loop itself looks tools up at run time.
 *
 * [warning] explains why nothing was generated, or flags a result worth a second look: a fallback parse of the LLM
 * output, or an empty params object.
 *
 * [rawResponse] only ever carries the raw LLM text, and only when that text could not be used (it holds no JSON object,
 * or names a tool the agent does not have). It is null otherwise.
 */
@Schema(name = "LoopConfigGenerationResult")
data class LoopConfigGenerationResult(
    @field:Schema(
        description =
            "Generated search block: `{ \"search\": { \"tool\", \"params\" } }`. It is not a complete loop " +
                "configuration: the `act` block is missing and must be added by the caller. `search.tool` is the real " +
                "name of the tool, possibly prefixed by its integration config (e.g. `USER_GROUP__searchAgentUsers` for " +
                "a requested `searchAgentUsers`). Null when generation failed (see `warning`).",
        type = "object",
        nullable = true,
    )
    val loopConfig: JsonNode?,
    @field:Schema(
        description = "Raw LLM response text, present only when it could not be used. Never an error message.",
        nullable = true,
    )
    val rawResponse: String? = null,
    @field:Schema(
        description =
            "Human-readable explanation: why nothing was generated, or a caveat on the result " +
                "(e.g. empty object generated, or fallback parse used).",
        nullable = true,
    )
    val warning: String? = null,
)
