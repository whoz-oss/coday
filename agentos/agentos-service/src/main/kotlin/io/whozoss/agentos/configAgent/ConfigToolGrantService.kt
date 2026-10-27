package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.tool.ToolResolverService
import mu.KLogging
import org.springframework.stereotype.Service
import java.time.Clock
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
 *
 * Called from `AgentServiceImpl.resolveAgentDefinition` only, so:
 * - chat runs (AgentSimple and AgentAdvanced) get the tools;
 * - the definition preview lists them, and `propose` answers `NO_CASE` there, since it has no case;
 * - `GET /api/tools` and `POST /api/tools/invoke` never see them: `ToolInvokeService` resolves
 *   integration configs only;
 * - a LOOP workflow can only call them by naming them, and `LoopWorkflowRunnerService` then takes the
 *   `AwaitAnswer` of `propose` as a failed step.
 */
@Service
class ConfigToolGrantService(
    private val configReadService: ConfigReadService,
    private val configPlanner: ConfigPlanner,
    private val toolResolverService: ToolResolverService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    fun grantTools(
        integrations: Map<String, List<String>?>?,
        agentName: String,
        namespaceId: UUID,
        userId: UUID?,
        caseId: UUID?,
    ): List<StandardTool<*>> {
        val allowed = integrations?.get(ConfigAgentIntegration.TYPE)
        val granted = integrations?.containsKey(ConfigAgentIntegration.TYPE) == true && allowed?.isEmpty() != true
        return if (granted) {
            logger.debug { "Granting ${ConfigAgentIntegration.TYPE} tools to agent '$agentName' (allowed=$allowed)" }
            listOf(
                ConfigReadTool(userId, namespaceId, configReadService, objectMapper),
                ConfigProposeTool(userId, namespaceId, caseId, agentName, configPlanner, objectMapper, clock),
            ).filter { toolResolverService.isToolAllowed(it.name, ConfigAgentIntegration.TYPE, allowed) }
        } else {
            emptyList()
        }
    }

    companion object : KLogging()
}
