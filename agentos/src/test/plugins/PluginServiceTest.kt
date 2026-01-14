package io.biznet.agentos.plugins

import io.biznet.agentos.agents.domain.Agent
import io.biznet.agentos.agents.domain.AgentStatus
import io.biznet.agentos.agents.domain.ContextType
import io.biznet.agentos.api.agent.AgentPlugin
import org.junit.jupiter.api.Test
import org.pf4j.DefaultPluginManager
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for PluginService
 */
class PluginServiceTest {
    @Test
    fun `should create plugin service`() {
        val pluginManager = DefaultPluginManager()
        val pluginService = PluginService(pluginManager)

        assertNotNull(pluginService)
    }

    @Test
    fun `should get empty plugin list initially`() {
        val pluginManager = DefaultPluginManager()
        val pluginService = PluginService(pluginManager)

        val plugins = pluginService.getLoadedPlugins()
        assertTrue(plugins.isEmpty())
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
