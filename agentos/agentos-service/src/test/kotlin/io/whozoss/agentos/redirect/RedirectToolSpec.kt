package io.whozoss.agentos.redirect

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.whozoss.agentos.agent.AgentInterrupt
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult

private val CONTEXT = mockk<ToolContext>(relaxed = true)

class RedirectToolSpec : StringSpec({

    fun makeEligible(vararg names: String) =
        names.map { RedirectTool.EligibleAgent(name = it, description = "Agent $it") }

    // -------------------------------------------------------------------------
    // description - integration rendering
    // -------------------------------------------------------------------------

    "description includes integration names when agent has integrations" {
        val tool = RedirectTool(
            configName = null,
            eligibleAgents = listOf(
                RedirectTool.EligibleAgent(
                    name = "AgentA",
                    description = "Does A",
                    integrations = listOf(
                        RedirectTool.Integration(name = "JIRA", allowedTools = null),
                        RedirectTool.Integration(name = "FILES", allowedTools = null),
                    ),
                ),
            ),
        )
        tool.description shouldBe """
            Route the current request to another agent. Use this when the request is better handled by a specialised agent.
            Available agents:
              - AgentA: Does A
                Integrations:
                  - JIRA
                  - FILES
        """.trimIndent()
    }

    "description includes allowed tools when integration has a whitelist" {
        val tool = RedirectTool(
            configName = null,
            eligibleAgents = listOf(
                RedirectTool.EligibleAgent(
                    name = "AgentA",
                    description = "Does A",
                    integrations = listOf(
                        RedirectTool.Integration(name = "JIRA", allowedTools = listOf("GetIssue", "PostComment")),
                    ),
                ),
            ),
        )
        tool.description shouldBe """
            Route the current request to another agent. Use this when the request is better handled by a specialised agent.
            Available agents:
              - AgentA: Does A
                Integrations:
                  - JIRA: GetIssue, PostComment
        """.trimIndent()
    }

    "description omits integrations section when agent has no integrations" {
        val tool = RedirectTool(
            configName = null,
            eligibleAgents = listOf(
                RedirectTool.EligibleAgent(name = "AgentA", description = "Does A"),
            ),
        )
        tool.description shouldBe """
            Route the current request to another agent. Use this when the request is better handled by a specialised agent.
            Available agents:
              - AgentA: Does A
        """.trimIndent()
    }

    "description mixes agents with and without integrations" {
        val tool = RedirectTool(
            configName = null,
            eligibleAgents = listOf(
                RedirectTool.EligibleAgent(
                    name = "AgentA",
                    description = "Does A",
                    integrations = listOf(
                        RedirectTool.Integration(name = "JIRA", allowedTools = listOf("GetIssue")),
                    ),
                ),
                RedirectTool.EligibleAgent(name = "AgentB", description = "Does B"),
            ),
        )
        tool.description shouldBe """
            Route the current request to another agent. Use this when the request is better handled by a specialised agent.
            Available agents:
              - AgentA: Does A
                Integrations:
                  - JIRA: GetIssue
              - AgentB: Does B
        """.trimIndent()
    }

    // -------------------------------------------------------------------------
    // execute - null input
    // WZ-31894: previously threw error("RedirectTool: agentName is required but was not provided by the LLM")
    // now returns a human-readable string so the LLM can surface it gracefully
    // -------------------------------------------------------------------------

    "execute returns a human-readable string when input is null instead of throwing" {
        val tool = RedirectTool(configName = null, eligibleAgents = makeEligible("AgentA"))
        val result = tool.execute(null, CONTEXT)
        result.output shouldBe "Agent name is required."
        result.success shouldBe false
    }

    // -------------------------------------------------------------------------
    // execute - unknown agent
    // WZ-31894: previously threw error("...") exposing a raw enum list to the LLM
    // now returns a human-readable string so the LLM can surface it gracefully
    // -------------------------------------------------------------------------

    "execute returns a human-readable string for an unknown agent name instead of throwing" {
        val tool = RedirectTool(
            configName = null,
            eligibleAgents = makeEligible("AgentA", "AgentB"),
        )
        val result = tool.execute(RedirectTool.Input("UnknownAgent"), CONTEXT)
        result.output shouldBe "Agent does not exist."
        result.success shouldBe false
    }

    "execute returns error even when eligible agents list is empty" {
        val tool = RedirectTool(configName = null, eligibleAgents = emptyList())
        val result = tool.execute(RedirectTool.Input("AnyAgent"), CONTEXT)
        result.output shouldBe "Agent does not exist."
        result.success shouldBe false
    }

    "execute returns error when agent name differs by case" {
        // Agent name matching is exact (case-sensitive): 'agenta' != 'AgentA'
        val tool = RedirectTool(
            configName = null,
            eligibleAgents = makeEligible("AgentA"),
        )
        val result = tool.execute(RedirectTool.Input("agenta"), CONTEXT)
        result.output shouldBe "Agent does not exist."
        result.success shouldBe false
    }

    // -------------------------------------------------------------------------
    // execute - happy path: throws AgentInterrupt.Redirect
    // -------------------------------------------------------------------------

    "execute throws AgentInterrupt.Redirect for a known eligible agent" {
        val tool = RedirectTool(
            configName = null,
            eligibleAgents = makeEligible("AgentA", "AgentB"),
        )
        val ex = shouldThrow<AgentInterrupt.Redirect> {
            tool.execute(RedirectTool.Input("AgentA"), CONTEXT)
        }
        ex.targetAgentName shouldBe "AgentA"
    }

    "execute throws AgentInterrupt.Redirect targeting the exact requested agent" {
        val tool = RedirectTool(
            configName = null,
            eligibleAgents = makeEligible("AgentA", "AgentB", "AgentC"),
        )
        val ex = shouldThrow<AgentInterrupt.Redirect> {
            tool.execute(RedirectTool.Input("AgentC"), CONTEXT)
        }
        ex.targetAgentName shouldBe "AgentC"
    }
})
