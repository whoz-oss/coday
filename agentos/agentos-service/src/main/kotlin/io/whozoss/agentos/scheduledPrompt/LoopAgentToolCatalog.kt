package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.tool.ToolResolverService
import io.whozoss.agentos.workflow.ToolReference
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The tools the scheduler loop agent ([SchedulerProperties.loopAgentName]) is granted in a namespace:
 * the tools a workflow step may name there (handover D7). Used at scheduled-prompt save time to find
 * the step-1 tool and validate `workflowInput` against its input schema.
 */
fun interface LoopAgentToolCatalog {
    /**
     * The tool [reference] (bare or `CONFIG__tool`) as granted to the loop agent in [namespaceId]
     * (null = platform integrations only), or null when the loop agent does not exist or is not
     * granted such a tool.
     */
    fun findTool(
        namespaceId: UUID?,
        reference: String,
    ): StandardTool<*>?
}

/**
 * [LoopAgentToolCatalog] resolving the loop agent's tools exactly as a run does: its
 * `integrations` against the namespace's effective integration configs, through
 * [ToolResolverService].
 *
 * Resolved **without a user** (platform and namespace layers only, no credentials): schemas do not
 * depend on who saves the scheduled prompt, and the save request must not trigger an OAuth prompt.
 * A tool whose plugin needs credentials to be listed is therefore not found here.
 */
@Service
class IntegrationLoopAgentToolCatalog(
    private val agentConfigService: AgentConfigService,
    private val integrationConfigService: IntegrationConfigService,
    private val toolResolverService: ToolResolverService,
    private val schedulerProperties: SchedulerProperties,
) : LoopAgentToolCatalog {
    override fun findTool(
        namespaceId: UUID?,
        reference: String,
    ): StandardTool<*>? {
        val loopAgent = agentConfigService.findByName(null, schedulerProperties.loopAgentName) ?: return null
        val context =
            ToolContext(
                // Platform scope has no namespace; the nil UUID only satisfies the type, no namespace layer exists for it.
                namespaceId = namespaceId ?: NO_NAMESPACE,
                userId = null,
                userExternalId = null,
                caseEvents = emptyList(),
                agentName = loopAgent.name,
            )
        return toolResolverService
            .resolveToolsForRun(
                agentIntegrations = loopAgent.integrations,
                context = context,
                allIntegrationConfigs = integrationConfigService.findEffective(namespaceId, null),
            ).firstOrNull { ToolReference.matches(it.name, reference) }
    }

    private companion object {
        val NO_NAMESPACE: UUID = UUID(0L, 0L)
    }
}
