package io.whozoss.agentos.configAgent

import io.whozoss.agentos.agent.AgentConfigProperties
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.integrationConfig.IntegrationsProperties
import io.whozoss.agentos.messaging.MessagingBotRegistry
import io.whozoss.agentos.messaging.MessagingBotService
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.sdk.api.messaging.MessagingBotStatus
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import io.whozoss.agentos.user.UserService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * What the config agent reads about a namespace and a user, besides the configuration entities:
 * whether a member's message reaches an agent there ([readiness]), who the user is
 * ([capabilities]), and which agents have a bot on a messaging platform ([messaging]).
 */
@Service
class ConfigNamespaceDiagnosis(
    private val namespaceService: NamespaceService,
    private val agentConfigService: AgentConfigService,
    private val agentConfigProperties: AgentConfigProperties,
    private val userService: UserService,
    private val integrationsProperties: IntegrationsProperties,
    private val configReadService: ConfigReadService,
    private val messagingBots: MessagingBotService,
    private val messagingBotRegistry: MessagingBotRegistry,
) {
    /**
     * Whether a member's message without `@mention` in [namespaceId] reaches an agent, resolved like
     * `CaseServiceImpl.selectDefaultAgent`: the namespace default, else the platform default, matched as a
     * case-insensitive name prefix among the enabled agents deployed on the namespace. When none is
     * found, the first condition that fails. It speaks for members, not for the caller: a super-admin's
     * own messages skip the deployment check, and an agent deployed on a user group only answers that
     * group.
     */
    fun readiness(namespaceId: UUID): NamespaceReadiness? =
        namespaceService.findById(namespaceId)?.let { namespace ->
            val defaultName = namespace.defaultAgentName ?: agentConfigProperties.agentName
            val agents = agentConfigService.findByNamespace(namespaceId) + agentConfigService.findByNamespace(null)
            val deployed = namespaceService.findDeployedAgentIds(namespaceId)
            val candidates = defaultName?.let { prefix -> agents.filter { it.name.lowercase().startsWith(prefix.lowercase()) } }.orEmpty()
            val resolved = candidates.firstOrNull { it.enabled && it.metadata.id in deployed }
            val status = defaultAgentStatus(defaultName, resolved, candidates)
            NamespaceReadiness(
                defaultAgentName = defaultName,
                defaultAgentSource =
                    when {
                        namespace.defaultAgentName != null -> DefaultAgentSource.NAMESPACE
                        defaultName != null -> DefaultAgentSource.PLATFORM
                        else -> null
                    },
                defaultAgentStatus = status,
                hint = status.hint,
                resolvedAgent = resolved?.name,
                deployedAgents = agents.filter { it.metadata.id in deployed }.map { DeployedAgent(name = it.name, enabled = it.enabled) },
            )
        }

    fun capabilities(
        callerId: UUID,
        namespaceId: UUID,
    ): Map<String, Any?> {
        val namespaceRole =
            when {
                configReadService.hasNamespaceRight(callerId, namespaceId, Action.WRITE) -> NamespaceRole.ADMIN
                configReadService.hasNamespaceRight(callerId, namespaceId, Action.READ) -> NamespaceRole.MEMBER
                else -> NamespaceRole.NONE
            }
        return mapOf(
            "userId" to callerId,
            "namespaceId" to namespaceId,
            "superAdmin" to (userService.findById(callerId)?.isAdmin == true),
            "namespaceRole" to namespaceRole,
            "userScopeDeniedIntegrationTypes" to integrationsProperties.userScopeDeniedTypes,
        )
    }

    /**
     * The bots of the agents of [namespaceId] on messaging platforms, and how an agent gets one. A bot is not a
     * proposal: it takes either the user's confirmation in the messaging application or a token they type, both
     * on the agent's page.
     */
    fun messaging(namespaceId: UUID): Map<String, Any?> =
        mapOf(
            "botCreationWithoutToken" to MessagingPlatform.entries.filter { messagingBotRegistry.manager(it) != null },
            "bots" to
                messagingBots.findAllAgentBots().filter { it.namespaceId == namespaceId }.map { bot ->
                    mapOf(
                        "agentId" to bot.agentId,
                        "agentName" to bot.agentId?.let { agentConfigService.findById(it)?.name },
                        "platform" to bot.platform,
                        "username" to bot.username,
                        "enabled" to bot.enabled,
                        "status" to (messagingBotRegistry.status(bot.id) ?: MessagingBotStatus.STARTING),
                    )
                },
            "howTo" to MESSAGING_HOW_TO,
        )

    private fun defaultAgentStatus(
        defaultName: String?,
        resolved: AgentConfig?,
        candidates: List<AgentConfig>,
    ): DefaultAgentStatus =
        when {
            defaultName == null -> DefaultAgentStatus.NOT_SET
            resolved != null -> DefaultAgentStatus.OK
            candidates.isEmpty() -> DefaultAgentStatus.NOT_FOUND
            candidates.none { it.enabled } -> DefaultAgentStatus.DISABLED
            else -> DefaultAgentStatus.NOT_DEPLOYED
        }

    private companion object {
        const val MESSAGING_HOW_TO =
            "An agent gets a bot on its page in AgentOS (the namespace's settings, Agents, the agent, section " +
                "Messaging), one per platform. Telegram: Create a bot, with no token, when botCreationWithoutToken " +
                "lists TELEGRAM (the user confirms in Telegram); or Use an existing bot, with the token of a bot the " +
                "user creates with @BotFather. Slack: the page gives the manifest of a Slack app; the user creates the " +
                "app from it at api.slack.com/apps, installs it in the workspace, and pastes its Bot User OAuth Token " +
                "(xoxb-) and an app-level token (xapp-, scope connections:write). Only an admin of the namespace can " +
                "do this. Each user then links their own account from their AgentOS profile, Messaging, Connect. The " +
                "change applies at once, no restart. Give the user these steps: no proposal can attach a bot."
    }
}
