package io.whozoss.agentos.service.plugins

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.whozoss.agentos.sdk.agent.AgentPlugin
import io.whozoss.agentos.service.agents.domain.Agent
import io.whozoss.agentos.service.agents.domain.AgentStatus
import io.whozoss.agentos.service.agents.domain.ContextType
import org.junit.jupiter.api.Test
import org.pf4j.DefaultPluginManager

/**
 * Tests for PluginService
 */
class PluginServiceTest {
    @Test
    fun `should create plugin service`() {
        val pluginManager = DefaultPluginManager()
        val pluginService = PluginService(pluginManager)

        pluginService.shouldNotBeNull()
    }

    @Test
    fun `should get empty plugin list initially`() {
        val pluginManager = DefaultPluginManager()
        val pluginService = PluginService(pluginManager)

        val plugins = pluginService.getLoadedPlugins()
        plugins shouldHaveSize 0
    }
}

/**
 * Mock agent plugin for testing
 */
class MockAgentPlugin : AgentPlugin {
    override fun getPluginId(): String = "mock-plugin"

    override fun getAgents(): List<Agent> =
        listOf(
            Agent(
                id = "mock-agent",
                name = "Mock Agent",
                description = "Test agent",
                version = "1.0.0",
                capabilities = listOf("mock"),
                requiredContext = setOf(ContextType.GENERAL),
                tags = setOf("test"),
                priority = 5,
                status = AgentStatus.ACTIVE,
            ),
        )
}
