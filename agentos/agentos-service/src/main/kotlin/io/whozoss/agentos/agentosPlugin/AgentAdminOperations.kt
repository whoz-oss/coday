package io.whozoss.agentos.agentosPlugin

import io.whozoss.agentos.agentConfig.AgentConfig
import java.util.UUID

/**
 * Collaborator interface for all agent administration operations exposed by the AGENTOS integration.
 *
 * Each method applies its own permission guard before delegating to the service layer:
 * - `userId == null` is always denied without consulting the permission graph (fail-closed).
 * - The required permission level per operation is:
 *   - **list / get** : Namespace READ (list), then AgentConfig READ on the resolved entity (get)
 *   - **create** : Namespace WRITE
 *   - **update / enable / disable** : AgentConfig WRITE on the resolved entity
 *
 * Methods return `null` on any denial (permission refused, entity not found) so that tools
 * can map the absence of a result to the appropriate error type without leaking existence
 * information.
 */
interface AgentAdminOperations {
    fun listAgents(namespaceId: UUID, userId: UUID?, withDisabled: Boolean): List<AgentConfig>?
    fun getAgent(namespaceId: UUID, userId: UUID?, name: String): AgentConfig?
    fun createAgent(namespaceId: UUID, userId: UUID?, input: CreateAgentTool.Input): AgentConfig?
    fun updateAgent(namespaceId: UUID, userId: UUID?, input: UpdateAgentTool.Input): AgentConfig?
    fun enableAgent(namespaceId: UUID, userId: UUID?, name: String): AgentConfig?
    fun disableAgent(namespaceId: UUID, userId: UUID?, name: String): AgentConfig?
    fun deployAgentOnNamespace(namespaceId: UUID, userId: UUID?, name: String): AgentConfig?
    fun undeployAgentFromNamespace(namespaceId: UUID, userId: UUID?, name: String): AgentConfig?
}
