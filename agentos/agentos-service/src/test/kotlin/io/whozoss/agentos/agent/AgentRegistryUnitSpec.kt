package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.agent.AgentDefinition
import io.whozoss.agentos.sdk.agent.AgentStatus

class AgentRegistryUnitSpec : StringSpec({

    fun registry(): AgentRegistry {
        val discoveryService = mockk<AgentDiscoveryService>()
        every { discoveryService.discoverAgents() } returns emptyList()
        return AgentRegistry(discoveryService)
    }

    fun registryWithAgents(): AgentRegistry {
        val r = registry()
        r.registerAgent(AgentDefinition(
            id = "code-reviewer", name = "Code Reviewer", description = "Reviews code",
            version = "1.0.0", capabilities = listOf("code-review", "test-generation"),
            requiredContext = setOf("CODE_REVIEW"), tags = setOf("quality"), priority = 8,
        ))
        r.registerAgent(AgentDefinition(
            id = "security-scanner", name = "Security Scanner", description = "Scans for security issues",
            version = "1.0.0", capabilities = listOf("security-scan"),
            requiredContext = setOf("GENERAL"), tags = setOf("security"), priority = 10,
        ))
        r.registerAgent(AgentDefinition(
            id = "general-helper", name = "General Helper", description = "General purpose agent",
            version = "1.0.0", capabilities = listOf("help"),
            requiredContext = setOf("GENERAL"), tags = setOf("general"), priority = 5,
        ))
        return r
    }

    "should register and retrieve agent" {
        val registry = registry()
        val agent = AgentDefinition(
            id = "test-agent", name = "Test Agent", description = "A test agent",
            version = "1.0.0", capabilities = listOf("test"), requiredContext = setOf("GENERAL"),
        )

        val registered = registry.registerAgent(agent)
        registered shouldBe agent
        registry.getAgent("test-agent") shouldBe agent
    }

    "should update existing agent" {
        val registry = registry()
        val agent = AgentDefinition(
            id = "update-test", name = "Original Name", description = "Original description",
            version = "1.0.0", capabilities = listOf("test"), requiredContext = setOf("GENERAL"),
        )
        registry.registerAgent(agent)

        val result = registry.updateAgent("update-test", agent.copy(name = "Updated Name"))

        result.shouldNotBeNull()
        result.name shouldBe "Updated Name"
    }

    "should unregister agent" {
        val registry = registry()
        val agent = AgentDefinition(
            id = "delete-test", name = "Delete Test", description = "To be deleted",
            version = "1.0.0", capabilities = listOf("test"), requiredContext = setOf("GENERAL"),
        )
        registry.registerAgent(agent)

        registry.unregisterAgent("delete-test").shouldBeTrue()
        registry.getAgent("delete-test").shouldBeNull()
    }

    "should return all agents" {
        val registry = registry()
        registry.registerAgent(AgentDefinition(
            id = "all-test", name = "All Test", description = "Test",
            version = "1.0.0", capabilities = listOf("test"), requiredContext = setOf("GENERAL"),
        ))

        registry.getAllAgents().shouldNotBeEmpty()
    }

    "findAgents should find agents by context type" {
        val response = registryWithAgents().findAgents(AgentContext(contextTypes = setOf("CODE_REVIEW")))

        response.agents.shouldNotBeEmpty()
        response.agents.all { it.requiredContext.contains("CODE_REVIEW") }.shouldBeTrue()
    }

    "findAgents should find agents by capability" {
        val response = registryWithAgents().findAgents(AgentContext(capabilities = setOf("test-generation")))

        response.agents.shouldNotBeEmpty()
        response.agents.all { it.capabilities.contains("test-generation") }.shouldBeTrue()
    }

    "findAgents should find agents by tags" {
        val response = registryWithAgents().findAgents(AgentContext(tags = setOf("security")))

        response.agents.shouldNotBeEmpty()
        response.agents.all { it.tags.contains("security") }.shouldBeTrue()
    }

    "findAgents should limit results with maxResults" {
        val response = registryWithAgents().findAgents(AgentContext(contextTypes = setOf("GENERAL"), maxResults = 1))

        (response.agents.size <= 1).shouldBeTrue()
    }

    "findAgents should filter by minimum priority" {
        val response = registryWithAgents().findAgents(AgentContext(minPriority = 9))

        response.agents.shouldNotBeEmpty()
        response.agents.all { it.priority >= 9 }.shouldBeTrue()
    }

    "findAgents should exclude agents by status" {
        val registry = registryWithAgents()
        registry.registerAgent(AgentDefinition(
            id = "inactive-agent", name = "Inactive Agent", description = "An inactive agent",
            version = "1.0.0", capabilities = listOf("test"),
            requiredContext = setOf("GENERAL"), status = AgentStatus.INACTIVE,
        ))

        val response = registry.findAgents(AgentContext(excludeStatuses = setOf(AgentStatus.INACTIVE)))

        response.agents.any { it.status == AgentStatus.INACTIVE }.shouldBeFalse()
    }

    "findAgents should sort agents by priority descending" {
        val response = registryWithAgents().findAgents(AgentContext(contextTypes = setOf("GENERAL")))

        for (i in 0 until response.agents.size - 1) {
            (response.agents[i].priority >= response.agents[i + 1].priority).shouldBeTrue()
        }
    }
})
