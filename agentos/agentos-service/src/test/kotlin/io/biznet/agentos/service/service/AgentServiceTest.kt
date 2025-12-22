package io.biznet.agentos.service.service

import io.biznet.agentos.sdk.AgentInput
import io.biznet.agentos.sdk.AgentMetadata
import io.biznet.agentos.sdk.AgentOutput
import io.biznet.agentos.sdk.AgentPlugin
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.pf4j.PluginManager

class AgentServiceTest :
    StringSpec({

        val pluginManager = mockk<PluginManager>()
        val agentService = AgentService(pluginManager)

        "listAgents should return metadata from all plugins" {
            val mockAgent1 =
                mockk<AgentPlugin> {
                    every { getMetadata() } returns
                        AgentMetadata(
                            name = "agent1",
                            description = "First agent",
                            version = "1.0.0",
                        )
                }

            val mockAgent2 =
                mockk<AgentPlugin> {
                    every { getMetadata() } returns
                        AgentMetadata(
                            name = "agent2",
                            description = "Second agent",
                            version = "2.0.0",
                        )
                }

            every { pluginManager.getExtensions(AgentPlugin::class.java) } returns listOf(mockAgent1, mockAgent2)

            val agents = agentService.listAgents()

            agents.size shouldBe 2
            agents[0].name shouldBe "agent1"
            agents[1].name shouldBe "agent2"
        }

        "executeAgent should throw exception when agent not found" {
            every { pluginManager.getExtensions(AgentPlugin::class.java) } returns emptyList()

            shouldThrow<IllegalArgumentException> {
                agentService.executeAgent("unknown-agent", AgentInput("test"))
            }
        }

        "executeAgent should call correct agent" {
            val mockAgent =
                mockk<AgentPlugin> {
                    every { getMetadata() } returns
                        AgentMetadata(
                            name = "test-agent",
                            description = "Test",
                            version = "1.0.0",
                        )
                    coEvery { execute(any()) } returns
                        AgentOutput(
                            message = "Test response",
                        )
                }

            every { pluginManager.getExtensions(AgentPlugin::class.java) } returns listOf(mockAgent)

            val input = AgentInput(message = "test input")
            val output = agentService.executeAgent("test-agent", input)

            output.message shouldBe "Test response"
        }
    })
