package io.whozoss.agentos.agentosPlugin

import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Single implementation of [AgentAdminOperations].
 *
 * Enforces permission guards before every service call. All operations are fail-closed:
 * a null [userId] is rejected immediately, and any permission denial returns null without
 * leaking details about the entity's existence.
 */
@Service
class AgentAdminOperationsImpl(
    private val agentConfigService: AgentConfigService,
    private val permissionService: PermissionService,
) : AgentAdminOperations {

    override fun listAgents(namespaceId: UUID, userId: UUID?, withDisabled: Boolean): List<AgentConfig>? {
        if (!canReadNamespace(namespaceId, userId, operation = "listAgents")) return null
        return agentConfigService.findByNamespace(namespaceId, withDisabled)
    }

    /**
     * Permission is checked on the [AgentConfig] entity, not on the namespace.
     *
     * [PermissionService.hasPermission] for [EntityType.AGENT_CONFIG] already resolves
     * transitive access: a namespace MEMBER inherits READ on all its AgentConfigs via
     * [io.whozoss.agentos.permissions.Neo4jPermissionRepository.hasTransitivePermission].
     * Adding a prior NAMESPACE READ check would turn that OR (direct entity relation OR
     * transitive namespace) into an AND, incorrectly blocking a user who holds a direct
     * relation on the AgentConfig but is not a namespace member.
     *
     * [listAgents] correctly checks NAMESPACE READ instead, because it enumerates at
     * namespace scope and has no single entity id to check against.
     */
    override fun getAgent(namespaceId: UUID, userId: UUID?, name: String): AgentConfig? {
        if (userId == null) {
            logger.debug { "[AgentosPlugin] getAgent denied: no userId" }
            return null
        }
        val agent = findAgentByName(namespaceId, name) ?: return null
        if (!canReadAgent(agent, userId, operation = "getAgent")) return null
        return agent
    }

    override fun createAgent(namespaceId: UUID, userId: UUID?, input: CreateAgentTool.Input): AgentConfig? {
        if (!canWriteNamespace(namespaceId, userId, operation = "createAgent")) return null
        // Name uniqueness is enforced by AgentConfigService.create() — no pre-check needed.
        // An IllegalArgumentException from the service maps to PERMISSION_DENIED in the tool
        // (duplicate name = cannot create).
        return runCatching {
            agentConfigService.create(
                AgentConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = input.name,
                    description = input.description,
                    instructions = input.instructions,
                    modelName = input.modelName,
                    integrations = input.integrations,
                    subAgents = input.subAgents?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() },
                    advancedExecution = input.advancedExecution,
                ),
            )
        }.onFailure { e ->
            logger.debug { "[AgentosPlugin] createAgent failed: ${e.message}" }
        }.getOrNull()
    }

    override fun updateAgent(namespaceId: UUID, userId: UUID?, input: UpdateAgentTool.Input): AgentConfig? {
        if (userId == null) {
            logger.debug { "[AgentosPlugin] updateAgent denied: no userId" }
            return null
        }
        val agent = findAgentByName(namespaceId, input.name) ?: return null
        if (!canWriteAgent(agent, userId, operation = "updateAgent")) return null
        return agentConfigService.update(
            agent.copy(
                description = input.description ?: agent.description,
                instructions = input.instructions ?: agent.instructions,
                modelName = input.modelName ?: agent.modelName,
                integrations = input.integrations ?: agent.integrations,
                subAgents =
                    input.subAgents?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                        ?: agent.subAgents,
                advancedExecution = input.advancedExecution ?: agent.advancedExecution,
            ),
        )
    }

    override fun enableAgent(namespaceId: UUID, userId: UUID?, name: String): AgentConfig? {
        if (userId == null) {
            logger.debug { "[AgentosPlugin] enableAgent denied: no userId" }
            return null
        }
        val agent = findAgentByName(namespaceId, name) ?: return null
        if (!canWriteAgent(agent, userId, operation = "enableAgent")) return null
        return agentConfigService.enable(agent.metadata.id)
    }

    override fun disableAgent(namespaceId: UUID, userId: UUID?, name: String): AgentConfig? {
        if (userId == null) {
            logger.debug { "[AgentosPlugin] disableAgent denied: no userId" }
            return null
        }
        val agent = findAgentByName(namespaceId, name) ?: return null
        if (!canWriteAgent(agent, userId, operation = "disableAgent")) return null
        return agentConfigService.disable(agent.metadata.id)
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Looks up an agent by name (case-insensitive) across all agents in the namespace,
     * including disabled ones. Returns null when no match is found.
     */
    private fun findAgentByName(namespaceId: UUID, name: String): AgentConfig? =
        agentConfigService
            .findByNamespace(namespaceId, withDisabled = true)
            .firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun canReadNamespace(namespaceId: UUID, userId: UUID?, operation: String): Boolean {
        if (userId == null) {
            logger.debug { "[AgentosPlugin] $operation denied: no userId" }
            return false
        }
        if (!permissionService.hasPermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)) {
            logger.debug { "[AgentosPlugin] $operation denied: user $userId lacks READ on namespace $namespaceId" }
            return false
        }
        return true
    }

    private fun canWriteNamespace(namespaceId: UUID, userId: UUID?, operation: String): Boolean {
        if (userId == null) {
            logger.debug { "[AgentosPlugin] $operation denied: no userId" }
            return false
        }
        if (!permissionService.hasPermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)) {
            logger.debug { "[AgentosPlugin] $operation denied: user $userId lacks WRITE on namespace $namespaceId" }
            return false
        }
        return true
    }

    private fun canReadAgent(agent: AgentConfig, userId: UUID, operation: String): Boolean {
        if (!permissionService.hasPermission(userId.toString(), EntityType.AGENT_CONFIG, agent.id.toString(), Action.READ)) {
            logger.debug { "[AgentosPlugin] $operation denied: user $userId lacks READ on agent ${agent.id}" }
            return false
        }
        return true
    }

    private fun canWriteAgent(agent: AgentConfig, userId: UUID, operation: String): Boolean {
        if (!permissionService.hasPermission(userId.toString(), EntityType.AGENT_CONFIG, agent.id.toString(), Action.WRITE)) {
            logger.debug { "[AgentosPlugin] $operation denied: user $userId lacks WRITE on agent ${agent.id}" }
            return false
        }
        return true
    }

    companion object : KLogging()
}
