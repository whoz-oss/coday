package io.whozoss.agentos.plugins.codebased

import io.whozoss.agentos.agents.domain.Agent
import io.whozoss.agentos.agents.domain.AgentStatus
import io.whozoss.agentos.agents.domain.ContextType
import io.whozoss.agentos.api.agent.AgentPlugin
import org.pf4j.Extension

/**
 * Provides hardcoded agents defined in code
 */
@Extension
class CodeBasedAgentProvider : AgentPlugin {
    override fun getPluginId(): String = "code-based-agents"

    override fun getVersion(): String = "1.0.0"

    override fun getDescription(): String = "Provides hardcoded agents defined in Kotlin code"

    override fun getAgents(): List<Agent> =
        listOf(
            createWhozDiscover(),
            createRescheduleAgent(),
            createTalentRequestEnricherAgent(),
            createStaffingAgent(),
        )

    private fun createWhozDiscover() =
        Agent(
            id = "whoz-discover",
            name = "Whoz Discover",
            description = "Help discovering Whoz capabilities.",
            version = "1.0.0",
            capabilities =
                listOf(
                    "discovering",
                    "helping",
                    "delegation",
                ),
            requiredContext = setOf(ContextType.GENERAL, ContextType.PERSONA),
            tags = setOf("whoz", "help", "conversation", "discovery"),
            priority = 8,
            status = AgentStatus.ACTIVE,
        )

    private fun createStaffingAgent() =
        Agent(
            id = "staffing-agent",
            name = "Staffing Agent",
            description = "Assist in staffing process",
            version = "1.0.0",
            capabilities = listOf("staffing", "whoz"),
            requiredContext = setOf(ContextType.PERSONA, ContextType.WORKSPACE, ContextType.GENERAL, ContextType.SCREEN),
            tags = setOf("whoz", "staffing"),
            priority = 1,
        )

    private fun createRescheduleAgent() =
        Agent(
            id = "reschedule-agent",
            name = "Reschedule Agent",
            description = "Assist in staffing process",
            version = "1.0.0",
            capabilities = listOf("smart-schedule", "whoz"),
            requiredContext = setOf(ContextType.PERSONA, ContextType.WORKSPACE, ContextType.GENERAL),
            tags = setOf("whoz", "staffing", "smart-schedule"),
            priority = 2,
        )

    private fun createTalentRequestEnricherAgent() =
        Agent(
            id = "talent-request-enricher",
            name = "Talent Request Enricher Agent",
            description = "Enrich a talent request",
            version = "1.0.0",
            capabilities = listOf("talent request", "parsing", "whoz"),
            requiredContext = setOf(ContextType.PERSONA, ContextType.WORKSPACE, ContextType.GENERAL, ContextType.ROLES),
            tags = setOf("whoz", "talent request", "demand", "parsing"),
            priority = 3,
        )

    override fun initialize() {
        println("CodeBasedAgentProvider initialized - providing ${getAgents().size} hardcoded agents")
    }

    override fun destroy() {
        println("CodeBasedAgentProvider destroyed")
    }
}
