package io.whozoss.agentos.sdk

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.agent.AgentPlugin

/**
 * Tests for AgentPlugin interface default implementations.
 */
class AgentPluginTest : DescribeSpec({

    describe("AgentPlugin defaults") {

        val minimalPlugin = object : AgentPlugin {
            override fun getPluginId(): String = "test-plugin"
            override fun getAgents(): List<Agent> = emptyList()
        }

        it("should have default version 1.0.0") {
            minimalPlugin.getVersion() shouldBe "1.0.0"
        }

        it("should have empty description by default") {
            minimalPlugin.getDescription() shouldBe ""
        }

        it("should allow initialize without implementation") {
            // Should not throw
            minimalPlugin.initialize()
        }

        it("should allow destroy without implementation") {
            // Should not throw
            minimalPlugin.destroy()
        }
    }
})
