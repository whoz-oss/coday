package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.casePlugin.BatchCaseStarterTool
import io.whozoss.agentos.casePlugin.CaseIntegrationConfigSeeder
import io.whozoss.agentos.sdk.api.agentConfig.ExecutionMode
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.userGroup.ListAgentUsersTool
import io.whozoss.agentos.userGroup.UserGroupIntegrationConfigSeeder
import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Seeds, at startup, the platform `LOOP` agent that runs the workflow of every scheduled run
 * ([SchedulerProperties.loopAgentName], handover D7).
 *
 * It is granted the two AgentOS step tools of the platform user-group workflow:
 * `USER_GROUP: [listAgentUsers]` and `CASE: [BatchCaseStarter]` (the latter is only ever granted by
 * name, see [io.whozoss.agentos.tool.RequiresExplicitGrant]). The Whoz search tools of the other
 * workflows are added to its `integrations` by the Whoz configuration, not here.
 *
 * Existence, not content: created only when absent, never updated or deleted. Once it exists its
 * integrations belong to the administrator (and to Whoz's configuration). Ordered after the
 * USER_GROUP (100) and CASE (102) integration seeders, whose configs it names.
 */
@Service
@Order(104)
class SchedulerLoopAgentSeeder(
    private val agentConfigService: AgentConfigService,
    private val schedulerProperties: SchedulerProperties,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = seed()

    internal fun seed() {
        if (agentConfigService.findByName(null, schedulerProperties.loopAgentName) != null) return
        try {
            agentConfigService.create(loopAgent(schedulerProperties.loopAgentName))
            logger.info { "[SchedulerLoopAgentSeeder] Created platform LOOP agent '${schedulerProperties.loopAgentName}'" }
        } catch (e: IllegalArgumentException) {
            // Another instance created it concurrently (duplicate name): the goal is reached.
            logger.info { "[SchedulerLoopAgentSeeder] '${schedulerProperties.loopAgentName}' created concurrently: ${e.message}" }
        }
    }

    companion object : KLogging() {
        fun loopAgent(name: String): AgentConfig =
            AgentConfig(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = null,
                name = name,
                description = "Runs the workflow of scheduled prompts (zero-LLM). Not meant to be addressed by users.",
                executionMode = ExecutionMode.LOOP,
                enabled = true,
                integrations =
                    mapOf(
                        UserGroupIntegrationConfigSeeder.DEFAULT_CONFIG_NAME to listOf(ListAgentUsersTool.TOOL_NAME),
                        CaseIntegrationConfigSeeder.DEFAULT_CONFIG_NAME to listOf(BatchCaseStarterTool.TOOL_NAME),
                    ),
            )
    }
}
