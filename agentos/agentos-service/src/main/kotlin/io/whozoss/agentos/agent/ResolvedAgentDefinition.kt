package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.api.agentConfig.ExecutionMode
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.skill.Skill
import java.util.UUID

/**
 * Fully-resolved agent definition, produced by the resolution phase and consumed
 * by the instantiation phase.
 *
 * This is the intermediate object that sits between:
 *   [AgentConfig] → [resolve] → [ResolvedAgentDefinition] → [instantiate] → [Agent]
 *
 * It can also be serialised directly for inspection purposes (e.g. debug endpoint)
 * without going through instantiation.
 *
 * @param agentConfigId The UUID of the source [io.whozoss.agentos.agentConfig.AgentConfig].
 * @param name The agent's display name.
 * @param systemPrompt The namespace context block (`## Context: <name>`) to be sent as a
 *   privileged system message, separate from the agent's own instructions. Null when no
 *   namespace context is available.
 * @param instructions The final system instructions (agent instructions + integrations +
 *   user context), after namespace context has been extracted into [systemPrompt].
 *   Null when no instructions are produced.
 * @param resolvedModelApiName The API-level model name, for display / inspection.
 * @param resolvedProviderName The provider name, for display / inspection.
 * @param resolvedModelId The UUID of the resolved [io.whozoss.agentos.sdk.aiProvider.AiModel], for display / inspection.
 * @param resolvedProviderId The UUID of the resolved [io.whozoss.agentos.sdk.aiProvider.AiProvider], for display / inspection.
 * @param resolvedModel The fully-resolved [AiModel], passed directly to the instantiation phase.
 * @param resolvedProvider The fully-resolved [AiProvider] (after overlay), passed directly to the instantiation phase.
 * @param tools The resolved tool set, filtered and scoped to this agent.
 * @param skills The resolved skill catalogue for this agent, as produced by
 *   [io.whozoss.agentos.skill.SkillService.findSkills] from [AgentConfig.skillSelectors]. This is
 *   the exact list rendered into [instructions] by
 *   [io.whozoss.agentos.skill.SkillCatalogRenderer] and bound to the granted
 *   `readSkill` / `readSkillResource` tools — not a second resolution — so an inspection of this
 *   field reports what the agent actually received. Empty when no selectors are configured.
 * @param executionMode The resolved execution mode for this agent (SIMPLE, ADVANCED, or LOOP).
 * @param namespaceId The namespace this agent is scoped to.
 * @param userId The user the agent is built for, or null for anonymous / system runs.
 * @param redirectGuideline Merged redirect guideline text: the `guideline` parameter of every
 *   REDIRECT integration config referenced by this agent (via [AgentConfig.integrations]),
 *   sorted by config name and concatenated, or null when none of them carries a non-blank
 *   guideline. Its destination in the built [Agent] depends on [advancedExecution]: for an
 *   `AgentAdvanced` it is passed to [AgentAdvancedContext.redirectGuideline] and consumed by
 *   [AgentIntentionGenerator]'s planning prompt; for an `AgentSimple` it is appended to
 *   [instructions] instead. See [io.whozoss.agentos.agent.AgentServiceImpl.resolveRedirectGuideline].
 * @param loopConfig Default [io.whozoss.agentos.workflow.AgentLoopPayload] for LOOP-mode agents,
 *   carried from [io.whozoss.agentos.agentConfig.AgentConfig.loopConfig]. Null for SIMPLE/ADVANCED
 *   agents or when no default was configured.
 * @param maxToolRounds The namespace's own override of the maximum tool rounds of a run
 *   ([io.whozoss.agentos.namespace.Namespace.maxToolRounds]), or null when the namespace sets none
 *   and the platform default (`agentos.limits.agent-max-iterations`) applies. Kept as the override,
 *   not the effective value, so the platform fallback stays decided in one place per consumer.
 */
data class ResolvedAgentDefinition(
    val agentConfigId: UUID,
    val name: String,
    val systemPrompt: String?,
    val instructions: String?,
    val resolvedModelApiName: String,
    val resolvedProviderName: String,
    val resolvedModelId: UUID,
    val resolvedProviderId: UUID,
    val resolvedModel: AiModel,
    val resolvedProvider: AiProvider,
    val tools: Collection<StandardTool<*>>,
    val skills: List<Skill> = emptyList(),
    val executionMode: ExecutionMode,
    val namespaceId: UUID,
    val userId: UUID?,
    val redirectGuideline: String? = null,
    val loopConfig: JsonNode? = null,
    val maxToolRounds: Int? = null,
)
