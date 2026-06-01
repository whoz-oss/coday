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
     * Resolve the canonical name for [namePart] within [namespaceId] by
     * [io.whozoss.agentos.agentConfig.AgentConfig] name matching,
     * without instantiating a full Agent.
     *
     * When [userId] is non-null, only agents accessible to that user
     * (via group or namespace membership) are considered — same semantics as
     * [io.whozoss.agentos.agentConfig.AgentConfigService.findAvailableByNamespaceIdAndUserId].
     * When [userId] is null (system / anonymous call), falls back to a plain
     * namespace-wide name lookup.
     *
     * Returns null if no matching [io.whozoss.agentos.agentConfig.AgentConfig] is found.
     */
    fun resolveAgentName(
        namePart: String,
        namespaceId: UUID,
        userId: UUID? = null,
    ): String?
}
