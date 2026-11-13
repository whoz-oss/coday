package io.whozoss.agentos.workflow

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

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
 * What [LoopWorkflowRunnerService] executes: a SEARCH, then an ACT per hit.
 *
 * It is either parsed as is from the legacy `search`/`act` format of an agent's `loopConfig`, or built by
 * [LoopStepsTranslator] from its `steps` format and the `loopData` of the run.
 *
 * Legacy format:
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
 * @param title Name of the started conversations. Null derives it from the prompt.
 * @param tool Name of the starter tool, a `FreeCaseStarterForUser` tool, as granted to the loop agent. Null
 *   finds it among the agent's tools.
 * @param scheduledPromptId Scheduled prompt each started case is linked to, once started. Null links nothing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentLoopAct(
    val agentName: String,
    @JsonAlias("promptTemplate")
    val prompt: String,
    val title: String? = null,
    val tool: String? = null,
    val scheduledPromptId: UUID? = null,
)
