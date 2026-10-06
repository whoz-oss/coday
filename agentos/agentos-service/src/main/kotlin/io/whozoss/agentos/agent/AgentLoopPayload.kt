package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.JsonNode

/**
 * Parsed representation of the JSON payload expected in the first user message of an AgentLoop case.
 *
 * @param tool        Name of the Search tool to invoke (e.g. "SearchTalents").
 * @param searchInput Opaque JSON block passed verbatim to the Search tool.
 * @param act         Configuration for the action to execute per entity.
 */
data class AgentLoopPayload(
    val tool: String,
    val searchInput: JsonNode,
    val act: AgentLoopAct,
)

/**
 * Configuration for the ACT phase: one case per entity.
 *
 * @param agentName      Name of the agent to invoke for each entity.
 * @param promptTemplate Template for the initial message sent to the agent.
 *   Use `{entityId}` as a placeholder for the entity external identifier.
 */
data class AgentLoopAct(
    val agentName: String,
    val promptTemplate: String,
)
