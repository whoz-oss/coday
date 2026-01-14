package io.biznet.agentos.agents.service

import io.biznet.agentos.agents.domain.Agent
import io.biznet.agentos.agents.domain.AgentContext
import io.biznet.agentos.agents.domain.AgentStatus
import io.biznet.agentos.agents.domain.ContextType
import io.biznet.agentos.plugins.AgentDiscoveryService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AgentRegistryTest {
    private lateinit var registry: AgentRegistry
    private lateinit var agentDiscoveryService: AgentDiscoveryService

    @BeforeEach
    fun setup() {
        registry = AgentRegistry(agentDiscoveryService)
    }

    @Test
    fun `should register and retrieve agent`() {
        val agent =
            Agent(
                id = "test-agent",
                name = "Test Agent",
                description = "A test agent",
                version = "1.0.0",
                capabilities = listOf("test"),
                requiredContext = setOf(ContextType.GENERAL),
            )

        val registered = registry.registerAgent(agent)
        assertEquals(agent, registered)

        val retrieved = registry.getAgent("test-agent")
        assertEquals(agent, retrieved)
    }

    @Test
    fun `should find agents by context type`() {
        val context =
            AgentContext(
                contextTypes = setOf(ContextType.CODE_REVIEW),
            )

        val response = registry.findAgents(context)

        assertTrue(response.agents.isNotEmpty())
        assertTrue(
            response.agents.all {
                it.requiredContext.contains(ContextType.CODE_REVIEW)
            },
        )
    }

    @Test
    fun `should find agents by capability`() {
        val context =
            AgentContext(
                capabilities = setOf("test-generation"),
            )

        val response = registry.findAgents(context)

        assertTrue(response.agents.isNotEmpty())
        assertTrue(
            response.agents.all {
                it.capabilities.contains("test-generation")
            },
        )
    }

    @Test
    fun `should find agents by tags`() {
        val context =
            AgentContext(
                tags = setOf("security"),
            )

        val response = registry.findAgents(context)

        assertTrue(response.agents.isNotEmpty())
        assertTrue(
            response.agents.all {
                it.tags.contains("security")
            },
        )
    }

    @Test
    fun `should limit results with maxResults`() {
        val context =
            AgentContext(
                contextTypes = setOf(ContextType.GENERAL),
                maxResults = 3,
            )

        val response = registry.findAgents(context)

        assertTrue(response.agents.size <= 3)
    }

    @Test
    fun `should filter by minimum priority`() {
        val context =
            AgentContext(
                minPriority = 9,
            )

        val response = registry.findAgents(context)

        assertTrue(response.agents.isNotEmpty())
        assertTrue(response.agents.all { it.priority >= 9 })
    }

    @Test
    fun `should exclude agents by status`() {
        // First, add an inactive agent
        registry.registerAgent(
            Agent(
                id = "inactive-agent",
                name = "Inactive Agent",
                description = "An inactive agent",
                version = "1.0.0",
                capabilities = listOf("test"),
                requiredContext = setOf(ContextType.GENERAL),
                status = AgentStatus.INACTIVE,
            ),
        )

        val context =
            AgentContext(
                excludeStatuses = setOf(AgentStatus.INACTIVE),
            )

        val response = registry.findAgents(context)

        assertFalse(response.agents.any { it.status == AgentStatus.INACTIVE })
    }

    @Test
    fun `should update existing agent`() {
        val agent =
            Agent(
                id = "update-test",
                name = "Original Name",
                description = "Original description",
                version = "1.0.0",
                capabilities = listOf("test"),
                requiredContext = setOf(ContextType.GENERAL),
            )

        registry.registerAgent(agent)

        val updated = agent.copy(name = "Updated Name")
        val result = registry.updateAgent("update-test", updated)

        assertNotNull(result)
        assertEquals("Updated Name", result?.name)
    }

    @Test
    fun `should unregister agent`() {
        val agent =
            Agent(
                id = "delete-test",
                name = "Delete Test",
                description = "To be deleted",
                version = "1.0.0",
                capabilities = listOf("test"),
                requiredContext = setOf(ContextType.GENERAL),
            )

        registry.registerAgent(agent)
        assertTrue(registry.unregisterAgent("delete-test"))
        assertNull(registry.getAgent("delete-test"))
    }

    @Test
    fun `should return all agents`() {
        val allAgents = registry.getAllAgents()
        assertTrue(allAgents.isNotEmpty())
    }

    @Test
    fun `should sort agents by priority descending`() {
        val context =
            AgentContext(
                contextTypes = setOf(ContextType.GENERAL),
            )

        val response = registry.findAgents(context)

        for (i in 0 until response.agents.size - 1) {
            assertTrue(response.agents[i].priority >= response.agents[i + 1].priority)
        }
    }
}
