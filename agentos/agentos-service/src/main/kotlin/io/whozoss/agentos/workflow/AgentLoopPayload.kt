package io.whozoss.agentos.workflow

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode

/**
 * SEARCH configuration: which tool to call and what parameters to pass.
 *
 * @param tool   Name of the Search tool to invoke (e.g. "SearchTalents").
 * @param params Opaque JSON block passed verbatim to the Search tool.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentLoopSearch(
    val tool: String,
    val params: JsonNode?,
)

/**
 * Parsed representation of the JSON payload expected in the `loopConfig` field of an AgentLoop.
 *
 * Expected JSON format:
 * ```json
 * {
 *   "search": {
 *     "tool": "SearchTalents",
 *     "params": { "endDatePeriod": ["THIS_WEEK"], "resolveTargets": ["OWNER"] }
 *   },
 *   "act": {
 *     "agentName": "talent-analyzer",
 *     "prompt": "Analyse this entity: {entityId}"
 *   }
 * }
 * ```
 *
 * @param search Configuration for the SEARCH phase (tool name and parameters).
 * @param act    Configuration for the action to execute per entity.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentLoopPayload(
    val search: AgentLoopSearch,
    val act: AgentLoopAct,
)

/**
 * Configuration for the ACT phase: one case per entity.
 *
 * @param agentName      Name of the agent to invoke for each entity.
 * @param prompt The initial message sent to the agent. The entity context is
 *   forwarded separately via `sessionContext["activeContext"]` rather than via string
 *   substitution — the agent reads the entity identifier from the context block.
 *   Accepts `promptTemplate` as a legacy alias for backward compatibility with existing
 *   agent configs that were created before the field was renamed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentLoopAct(
    val agentName: String,
    @JsonAlias("promptTemplate")
    val prompt: String,
)
