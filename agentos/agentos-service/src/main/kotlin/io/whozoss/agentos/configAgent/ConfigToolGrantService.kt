package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.tool.ToolResolverService
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Grants the built-in `AGENTOS_CONFIG` tools to an agent that names the integration.
 *
 * Same value semantics as any integration key: null grants every tool, a list grants the named
 * ones (bare `read` or prefixed `AGENTOS_CONFIG__read`), an empty list is an explicit opt-out. Unlike
 * the file exchange there is no platform default: an agent that does not name the key gets nothing.
 *
 * The tools are bound to the run's user, namespace, case and agent here, at grant time, because
 * the [io.whozoss.agentos.sdk.tool.ToolContext] the agents build at execution carries neither the
 * agent name nor the case reliably.
 */
@Service
class ConfigToolGrantService(
    private val configReadService: ConfigReadService,
    private val configPlanner: ConfigPlanner,
    private val toolResolverService: ToolResolverService,
    private val objectMapper: ObjectMapper,
) {
    fun grantTools(
        integrations: Map<String, List<String>?>?,
        agentName: String,
        namespaceId: UUID,
        userId: UUID?,
        caseId: UUID?,
    ): List<StandardTool<*>> {
        if (integrations == null || !integrations.containsKey(ConfigAgentIntegration.TYPE)) return emptyList()
        val allowed = integrations[ConfigAgentIntegration.TYPE]
        if (allowed != null && allowed.isEmpty()) return emptyList()
        logger.debug { "Granting ${ConfigAgentIntegration.TYPE} tools to agent '$agentName' (allowed=$allowed)" }
        return listOf(
            ConfigReadTool(userId, namespaceId, configReadService, objectMapper),
            ConfigProposeTool(userId, namespaceId, caseId, agentName, configPlanner, objectMapper),
        ).filter { toolResolverService.isToolAllowed(it.name, ConfigAgentIntegration.TYPE, allowed) }
    }

    companion object : KLogging()
}
