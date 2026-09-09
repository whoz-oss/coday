package io.whozoss.agentos.agentosPlugin

import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/**
 * Spring configuration for the AGENTOS internal integration.
 *
 * Declared in a dedicated `@Configuration` class (mirroring
 * [io.whozoss.agentos.casePlugin.CasePluginConfiguration]) to avoid a circular Spring
 * dependency. This class injects [AgentConfigRepository] directly rather than
 * [io.whozoss.agentos.agentConfig.AgentConfigService], because `AgentConfigServiceImpl`
 * depends on `AgentService` which depends on `ToolRegistryService` which collects all
 * `ToolPlugin` beans — creating a cycle if `AgentConfigService` were injected here.
 *
 * Permission model:
 * - **List** : Namespace READ
 * - **Get** : AgentConfig READ (checked on the id of the found entity)
 * - **Create** : Namespace WRITE
 * - **Update / Enable / Disable** : AgentConfig WRITE (checked on the id of the found entity)
 *
 * All lambdas are fail-closed: `userId == null` → deny. Anonymous / system calls have
 * no user identity to check against the permission graph.
 */
@Configuration
class AgentosAgentsPluginConfiguration(
    private val agentConfigRepository: AgentConfigRepository,
    private val permissionService: PermissionService,
) {
    @Bean
    fun agentosAgentsToolPlugin(): ToolPlugin =
        AgentosAgentsToolPlugin(
            listAgents = { namespaceId, userId, withDisabled ->
                if (userId == null) {
                    logger.debug { "[AgentosPlugin] listAgents denied: no userId" }
                    return@AgentosAgentsToolPlugin null
                }
                if (!permissionService.hasPermission(
                        userId.toString(),
                        EntityType.NAMESPACE,
                        namespaceId.toString(),
                        Action.READ,
                    )
                ) {
                    logger.debug { "[AgentosPlugin] listAgents denied: user $userId lacks READ on namespace $namespaceId" }
                    return@AgentosAgentsToolPlugin null
                }
                agentConfigRepository.findByParent(namespaceId, withDisabled)
            },
            getAgent = { namespaceId, userId, name ->
                if (userId == null) {
                    logger.debug { "[AgentosPlugin] getAgent denied: no userId" }
                    return@AgentosAgentsToolPlugin null
                }
                val agent =
                    agentConfigRepository
                        .findByParent(namespaceId, withDisabled = true)
                        .firstOrNull { it.name.equals(name, ignoreCase = true) }
                        ?: return@AgentosAgentsToolPlugin null
                if (!permissionService.hasPermission(
                        userId.toString(),
                        EntityType.AGENT_CONFIG,
                        agent.id.toString(),
                        Action.READ,
                    )
                ) {
                    logger.debug { "[AgentosPlugin] getAgent denied: user $userId lacks READ on agent ${agent.id}" }
                    return@AgentosAgentsToolPlugin null
                }
                agent
            },
            createAgent = { namespaceId, userId, input ->
                if (userId == null) {
                    logger.debug { "[AgentosPlugin] createAgent denied: no userId" }
                    return@AgentosAgentsToolPlugin null
                }
                if (!permissionService.hasPermission(
                        userId.toString(),
                        EntityType.NAMESPACE,
                        namespaceId.toString(),
                        Action.WRITE,
                    )
                ) {
                    logger.debug { "[AgentosPlugin] createAgent denied: user $userId lacks WRITE on namespace $namespaceId" }
                    return@AgentosAgentsToolPlugin null
                }
                // Reject if an agent with the same name already exists (case-insensitive)
                val existing =
                    agentConfigRepository
                        .findByParent(namespaceId, withDisabled = true)
                        .any { it.name.equals(input.name, ignoreCase = true) }
                if (existing) {
                    logger.debug { "[AgentosPlugin] createAgent denied: name '${input.name}' already exists in namespace $namespaceId" }
                    return@AgentosAgentsToolPlugin null
                }
                agentConfigRepository.save(
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
            },
            updateAgent = { namespaceId, userId, input ->
                if (userId == null) {
                    logger.debug { "[AgentosPlugin] updateAgent denied: no userId" }
                    return@AgentosAgentsToolPlugin null
                }
                val agent =
                    agentConfigRepository
                        .findByParent(namespaceId, withDisabled = true)
                        .firstOrNull { it.name.equals(input.name, ignoreCase = true) }
                        ?: return@AgentosAgentsToolPlugin null
                if (!permissionService.hasPermission(
                        userId.toString(),
                        EntityType.AGENT_CONFIG,
                        agent.id.toString(),
                        Action.WRITE,
                    )
                ) {
                    logger.debug { "[AgentosPlugin] updateAgent denied: user $userId lacks WRITE on agent ${agent.id}" }
                    return@AgentosAgentsToolPlugin null
                }
                agentConfigRepository.save(
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
            },
            enableAgent = { namespaceId, userId, name ->
                if (userId == null) {
                    logger.debug { "[AgentosPlugin] enableAgent denied: no userId" }
                    return@AgentosAgentsToolPlugin null
                }
                val agent =
                    agentConfigRepository
                        .findByParent(namespaceId, withDisabled = true)
                        .firstOrNull { it.name.equals(name, ignoreCase = true) }
                        ?: return@AgentosAgentsToolPlugin null
                if (!permissionService.hasPermission(
                        userId.toString(),
                        EntityType.AGENT_CONFIG,
                        agent.id.toString(),
                        Action.WRITE,
                    )
                ) {
                    logger.debug { "[AgentosPlugin] enableAgent denied: user $userId lacks WRITE on agent ${agent.id}" }
                    return@AgentosAgentsToolPlugin null
                }
                agentConfigRepository.save(agent.copy(enabled = true))
            },
            disableAgent = { namespaceId, userId, name ->
                if (userId == null) {
                    logger.debug { "[AgentosPlugin] disableAgent denied: no userId" }
                    return@AgentosAgentsToolPlugin null
                }
                val agent =
                    agentConfigRepository
                        .findByParent(namespaceId, withDisabled = true)
                        .firstOrNull { it.name.equals(name, ignoreCase = true) }
                        ?: return@AgentosAgentsToolPlugin null
                if (!permissionService.hasPermission(
                        userId.toString(),
                        EntityType.AGENT_CONFIG,
                        agent.id.toString(),
                        Action.WRITE,
                    )
                ) {
                    logger.debug { "[AgentosPlugin] disableAgent denied: user $userId lacks WRITE on agent ${agent.id}" }
                    return@AgentosAgentsToolPlugin null
                }
                agentConfigRepository.save(agent.copy(enabled = false))
            },
        )

    companion object : KLogging()
}
