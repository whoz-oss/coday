package io.whozoss.agentos.agent

import io.kotest.core.spec.style.DescribeSpec
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

class AgentRegistryTest :
    DescribeSpec({

        describe("AgentRegistry") {

            lateinit var agentDiscoveryService: AgentDiscoveryService
            lateinit var registry: AgentRegistry

            beforeEach {
                agentDiscoveryService = mockk()
                every { agentDiscoveryService.discoverAgents() } returns emptyList()
                registry = AgentRegistry(agentDiscoveryService)
            }

            describe("register and retrieve") {

                it("should register and retrieve agent") {
                    val agent =
                        AgentDefinition(
                            id = "test-agent",
                            name = "Test Agent",
                            description = "A test agent",
                            version = "1.0.0",
                            capabilities = listOf("test"),
                            requiredContext = setOf("GENERAL"),
                        )

                    val registered = registry.registerAgent(agent)
                    registered shouldBe agent

                    val retrieved = registry.getAgent("test-agent")
                    retrieved shouldBe agent
                }

                it("should update existing agent") {
                    val agent =
                        AgentDefinition(
                            id = "update-test",
                            name = "Original Name",
                            description = "Original description",
                            version = "1.0.0",
                            capabilities = listOf("test"),
                            requiredContext = setOf("GENERAL"),
                        )

                    registry.registerAgent(agent)

                    val updated = agent.copy(name = "Updated Name")
                    val result = registry.updateAgent("update-test", updated)

                    result.shouldNotBeNull()
                    result.name shouldBe "Updated Name"
                }

                it("should unregister agent") {
                    val agent =
                        AgentDefinition(
                            id = "delete-test",
                            name = "Delete Test",
                            description = "To be deleted",
                            version = "1.0.0",
                            capabilities = listOf("test"),
                            requiredContext = setOf("GENERAL"),
                        )

                    registry.registerAgent(agent)
                    registry.unregisterAgent("delete-test").shouldBeTrue()
                    registry.getAgent("delete-test").shouldBeNull()
                }

                it("should return all agents") {
                    val agent =
                        AgentDefinition(
                            id = "all-test",
                            name = "All Test",
                            description = "Test",
                            version = "1.0.0",
                            capabilities = listOf("test"),
                            requiredContext = setOf("GENERAL"),
                        )
                    registry.registerAgent(agent)

                    val allAgents = registry.getAllAgents()
                    allAgents.shouldNotBeEmpty()
                }
            }

            describe("findAgents") {

                beforeEach {
                    // Register some test agents
                    registry.registerAgent(
                        AgentDefinition(
                            id = "code-reviewer",
                            name = "Code Reviewer",
                            description = "Reviews code",
                            version = "1.0.0",
                            capabilities = listOf("code-review", "test-generation"),
                            requiredContext = setOf("CODE_REVIEW"),
                            tags = setOf("quality"),
                            priority = 8,
                        ),
                    )
                    registry.registerAgent(
                        AgentDefinition(
                            id = "security-scanner",
                            name = "Security Scanner",
                            description = "Scans for security issues",
                            version = "1.0.0",
                            capabilities = listOf("security-scan"),
                            requiredContext = setOf("GENERAL"),
                            tags = setOf("security"),
                            priority = 10,
                        ),
                    )
                    registry.registerAgent(
                        AgentDefinition(
                            id = "general-helper",
                            name = "General Helper",
                            description = "General purpose agent",
                            version = "1.0.0",
                            capabilities = listOf("help"),
                            requiredContext = setOf("GENERAL"),
                            tags = setOf("general"),
                            priority = 5,
                        ),
                    )
                }

                it("should find agents by context type") {
                    val context =
                        AgentContext(
                            contextTypes = setOf("CODE_REVIEW"),
                        )

                    val response = registry.findAgents(context)

                    response.agents.shouldNotBeEmpty()
                    response.agents.all { it.requiredContext.contains("CODE_REVIEW") }.shouldBeTrue()
                }

                it("should find agents by capability") {
                    val context =
                        AgentContext(
                            capabilities = setOf("test-generation"),
                        )

                    val response = registry.findAgents(context)

                    response.agents.shouldNotBeEmpty()
                    response.agents.all { it.capabilities.contains("test-generation") }.shouldBeTrue()
                }

                it("should find agents by tags") {
                    val context =
                        AgentContext(
                            tags = setOf("security"),
                        )

                    val response = registry.findAgents(context)

                    response.agents.shouldNotBeEmpty()
                    response.agents.all { it.tags.contains("security") }.shouldBeTrue()
                }

                it("should limit results with maxResults") {
                    val context =
                        AgentContext(
                            contextTypes = setOf("GENERAL"),
                            maxResults = 1,
                        )

                    val response = registry.findAgents(context)

                    (response.agents.size <= 1).shouldBeTrue()
                }

                it("should filter by minimum priority") {
                    val context =
                        AgentContext(
                            minPriority = 9,
                        )

                    val response = registry.findAgents(context)

                    response.agents.shouldNotBeEmpty()
                    response.agents.all { it.priority >= 9 }.shouldBeTrue()
                }

                it("should exclude agents by status") {
                    registry.registerAgent(
                        AgentDefinition(
                            id = "inactive-agent",
                            name = "Inactive Agent",
                            description = "An inactive agent",
                            version = "1.0.0",
                            capabilities = listOf("test"),
                            requiredContext = setOf("GENERAL"),
                            status = AgentStatus.INACTIVE,
                        ),
                    )

                    val context =
                        AgentContext(
                            excludeStatuses = setOf(AgentStatus.INACTIVE),
                        )

                    val response = registry.findAgents(context)

                    response.agents.any { it.status == AgentStatus.INACTIVE }.shouldBeFalse()
                }

                it("should sort agents by priority descending") {
                    val context =
                        AgentContext(
                            contextTypes = setOf("GENERAL"),
                        )

                    val response = registry.findAgents(context)

                    for (i in 0 until response.agents.size - 1) {
                        (response.agents[i].priority >= response.agents[i + 1].priority).shouldBeTrue()
                    }
                }
            }
        }
    })
