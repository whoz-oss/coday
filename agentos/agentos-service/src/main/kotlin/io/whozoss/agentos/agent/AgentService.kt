package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.agent.Agent

/**
 * Service for managing agent runtime instances.
 *
 * Agent-instantiating methods require an [AgentExecutionContext] so the constructed
 * agent is aware of the namespace and case it is running for. This context is used
 * today to inject the namespace description into the agent's system instructions;
 * it will also be used to scope tool resolution per namespace and user.
 *
 * Name-resolution methods ([getDefaultAgentName], [resolveAgentName]) do not
 * instantiate agents and therefore do not need a context.
 */
interface AgentService {
    /**
     * Find and instantiate an agent by exact or partial name match.
     * The agent is built with [context] so its instructions and tool set
     * are scoped to the given namespace and case.
     * Throws if no model matches [namePart].
     */
    fun findAgentByName(namePart: String, context: AgentExecutionContext): Agent

    /**
     * Instantiate all registered agents without any execution context.
     * Intended for registry inspection only — not for running agents.
     */
    fun listAgents(): List<Agent>

    /**
     * Get the default agent for cases where no agent is explicitly selected.
     * Returns null if no default is configured.
     */
    fun getDefaultAgent(context: AgentExecutionContext): Agent?

    /**
     * Get the name of the default agent without instantiating a full Agent.
     * Use this when only the agent's identity is needed (e.g. to emit an AgentSelectedEvent).
     * Returns null if no default is configured.
     */
    fun getDefaultAgentName(): String?

    /**
     * Resolve an agent's canonical name by partial name match, without instantiating a full Agent.
     * Use this when only the agent's identity is needed (e.g. to emit an AgentSelectedEvent).
     * Returns null if no match is found.
     */
    fun resolveAgentName(namePart: String): String?
}
