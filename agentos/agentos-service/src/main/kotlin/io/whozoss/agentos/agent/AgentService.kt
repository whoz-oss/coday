package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.agent.Agent
import java.util.UUID

/**
 * Service for managing agent runtime instances.
 *
 * Agent-instantiating methods require an [AgentExecutionContext] so the constructed
 * agent is aware of the namespace and case it is running for. This context is used
 * today to inject the namespace description into the agent's system instructions;
 * it will also be used to scope tool resolution per namespace and user.
 *
 * Name-resolution methods ([getDefaultAgentName], [resolveAgentName]) do not
 * instantiate agents but do require a [namespaceId] to scope the lookup against
 * the namespace's [io.whozoss.agentos.agentConfig.AgentConfig] and
 * [io.whozoss.agentos.aiModel.AiModel] entities.
 */
interface AgentService {
    /**
     * Find and instantiate an agent by exact or partial name match.
     * The agent is built with [context] so its instructions and tool set
     * are scoped to the given namespace and case.
     * Throws if no model matches [namePart].
     */
    fun findAgentByName(
        namePart: String,
        context: AgentExecutionContext,
    ): Agent

    /**
     * Get the default agent for cases where no agent is explicitly selected.
     * Returns null if the default [io.whozoss.agentos.agentConfig.AgentConfig]
     * has no [io.whozoss.agentos.agentConfig.AgentConfig.modelName] and no
     * [io.whozoss.agentos.aiModel.AiModel] is configured for the namespace.
     */
    fun getDefaultAgent(context: AgentExecutionContext): Agent?

    /**
     * Get the logical name of the default agent for [namespaceId] without
     * instantiating a full Agent.
     *
     * Always returns a non-null name: when no [io.whozoss.agentos.agentConfig.AgentConfig]
     * has been persisted for the namespace, the built-in fallback config name is returned.
     */
    fun getDefaultAgentName(namespaceId: UUID): String

    /**
     * Resolve the canonical name for [namePart] within [namespaceId] by
     * [io.whozoss.agentos.agentConfig.AgentConfig] name matching,
     * without instantiating a full Agent.
     * Returns null if no [io.whozoss.agentos.agentConfig.AgentConfig] matches.
     */
    fun resolveAgentName(
        namePart: String,
        namespaceId: UUID,
    ): String?
}
