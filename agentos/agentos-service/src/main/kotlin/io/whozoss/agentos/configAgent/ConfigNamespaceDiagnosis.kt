package io.whozoss.agentos.configAgent

import io.whozoss.agentos.agent.AgentConfigProperties
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.integrationConfig.IntegrationsProperties
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.security.declarative.AgentOsPermissionEvaluator
import io.whozoss.agentos.user.UserService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * What the config agent reads about a namespace and a user, besides the configuration entities:
 * whether a member's message reaches an agent there ([readiness]) and who the user is
 * ([capabilities]).
 */
@Service
class ConfigNamespaceDiagnosis(
    private val namespaceService: NamespaceService,
    private val agentConfigService: AgentConfigService,
    private val agentConfigProperties: AgentConfigProperties,
    private val userService: UserService,
    private val integrationsProperties: IntegrationsProperties,
    private val permissionEvaluator: AgentOsPermissionEvaluator,
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
                        namespace.defaultAgentName != null -> "namespace"
                        defaultName != null -> "platform"
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
                hasNamespaceRight(callerId, namespaceId, Action.WRITE) -> "ADMIN"
                hasNamespaceRight(callerId, namespaceId, Action.READ) -> "MEMBER"
                else -> "NONE"
            }
        return mapOf(
            "userId" to callerId,
            "namespaceId" to namespaceId,
            "superAdmin" to (userService.findById(callerId)?.isAdmin == true),
            "namespaceRole" to namespaceRole,
            "userScopeDeniedIntegrationTypes" to integrationsProperties.userScopeDeniedTypes,
        )
    }

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

    private fun hasNamespaceRight(
        callerId: UUID,
        namespaceId: UUID,
        action: Action,
    ): Boolean = permissionEvaluator.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), action)
}
