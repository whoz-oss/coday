package io.whozoss.agentos.workflow

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
 *     "promptTemplate": "Analyse this entity: {entityId}"
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
 * @param promptTemplate Template for the initial message sent to the agent.
 *   Use `{entityId}` as a placeholder for the entity external identifier.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentLoopAct(
    val agentName: String,
    val promptTemplate: String,
)
