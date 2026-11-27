package io.whozoss.agentos.agent

import io.whozoss.agentos.namespace.NamespaceService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The agent a message without `@mention` goes to when nothing in the case decides (a new case, or a
 * last agent that is gone): the namespace's `defaultAgentName`, else the platform's
 * `agentos.defaults.agent-name`, resolved for [userId] like any agent name, as a prefix among the
 * agents deployed to that user.
 *
 * The single place this rule lives: the case runtime routes with it, and the composer shows its
 * answer, so what the user is told and what happens cannot drift apart.
 */
@Component
class DefaultAgentResolver(
    private val namespaceService: NamespaceService,
    private val agentService: AgentService,
    private val agentConfigProperties: AgentConfigProperties,
) {
    fun resolve(
        namespaceId: UUID,
        userId: UUID?,
    ): DefaultAgentResolution {
        val namespaceDefault = namespaceService.findById(namespaceId)?.defaultAgentName
        val configuredName = namespaceDefault ?: agentConfigProperties.agentName
        val agentName =
            configuredName?.let {
                agentService.resolveAgentName(namePart = it, namespaceId = namespaceId, userId = userId)
            }
        return DefaultAgentResolution(
            configuredName = configuredName,
            fromNamespace = namespaceDefault != null,
            agentName = agentName,
        )
    }
}

/**
 * @property configuredName The name configured on the namespace, else on the platform; null when neither names one.
 * @property fromNamespace True when [configuredName] comes from the namespace, false when from the platform.
 * @property agentName The agent such a message reaches, or null when [configuredName] resolves to none for the user.
 *   The name matches as a prefix, so it may differ from [configuredName].
 */
data class DefaultAgentResolution(
    val configuredName: String?,
    val fromNamespace: Boolean,
    val agentName: String?,
)
