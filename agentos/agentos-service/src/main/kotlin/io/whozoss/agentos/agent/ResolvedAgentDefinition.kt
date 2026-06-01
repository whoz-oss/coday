package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.tool.StandardTool
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
 * @param instructions The final system instructions, after namespace / integration /
 *   user context blocks have been injected. Null when no instructions are produced.
 * @param resolvedModelApiName The API-level model name, for display / inspection.
 * @param resolvedProviderName The provider name, for display / inspection.
 * @param resolvedModelId The UUID of the resolved [io.whozoss.agentos.sdk.aiProvider.AiModel], for display / inspection.
 * @param resolvedProviderId The UUID of the resolved [io.whozoss.agentos.sdk.aiProvider.AiProvider], for display / inspection.
 * @param resolvedModel The fully-resolved [AiModel], passed directly to the instantiation phase.
 * @param resolvedProvider The fully-resolved [AiProvider] (after overlay), passed directly to the instantiation phase.
 * @param tools The resolved tool set, filtered and scoped to this agent.
 * @param advancedExecution Whether the agent should run in advanced multi-step mode.
 * @param namespaceId The namespace this agent is scoped to.
 * @param userId The user the agent is built for, or null for anonymous / system runs.
 */
data class ResolvedAgentDefinition(
    val agentConfigId: UUID,
    val name: String,
    val instructions: String?,
    val resolvedModelApiName: String,
    val resolvedProviderName: String,
    val resolvedModelId: UUID,
    val resolvedProviderId: UUID,
    val resolvedModel: AiModel,
    val resolvedProvider: AiProvider,
    val tools: Collection<StandardTool<*>>,
    val advancedExecution: Boolean,
    val namespaceId: UUID,
    val userId: UUID?,
)
