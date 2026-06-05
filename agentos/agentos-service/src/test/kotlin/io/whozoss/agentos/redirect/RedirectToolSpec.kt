package io.whozoss.agentos.redirect

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.agent.AgentInterrupt
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.util.UUID

private val NAMESPACE_ID: UUID = UUID.randomUUID()
private val CASE_ID: UUID = UUID.randomUUID()

private val CONTEXT = mockk<ToolContext>(relaxed = true)

class RedirectToolSpec : StringSpec({

    fun makeEligible(vararg names: String) =
        names.map { RedirectTool.EligibleAgent(name = it, description = "Agent $it") }

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

    // -------------------------------------------------------------------------
    // Redirect loop detection
    // -------------------------------------------------------------------------

    "detectRedirectLoop returns true when target appears REDIRECT_LOOP_THRESHOLD times in the window" {
        // caseEvents already contains THRESHOLD occurrences of AgentA this turn → loop detected.
        val tool = RedirectTool(configName = null, eligibleAgents = makeEligible("AgentA", "AgentB"))
        val context = mockk<ToolContext>(relaxed = true)
        val agentAId = UUID.nameUUIDFromBytes("AgentA".toByteArray())
        every { context.caseEvents } returns List(RedirectTool.REDIRECT_LOOP_THRESHOLD) {
            AgentSelectedEvent(
                metadata = EntityMetadata(),
                namespaceId = NAMESPACE_ID,
                caseId = CASE_ID,
                agentId = agentAId,
                agentName = "AgentA",
            )
        }

        val result = tool.execute(RedirectTool.Input("AgentA"), context)

        result.success shouldBe false
        result.output shouldBe "Redirect loop detected: 'AgentA' has already been invoked during this turn. " +
            "Stop redirecting and answer the user directly."
    }

    "detectRedirectLoop returns false when target appears fewer than REDIRECT_LOOP_THRESHOLD times" {
        // caseEvents contains THRESHOLD-1 occurrences of AgentA → one more redirect is still allowed.
        val tool = RedirectTool(configName = null, eligibleAgents = makeEligible("AgentA"))
        val context = mockk<ToolContext>(relaxed = true)
        val agentAId = UUID.nameUUIDFromBytes("AgentA".toByteArray())
        every { context.caseEvents } returns List(RedirectTool.REDIRECT_LOOP_THRESHOLD - 1) {
            AgentSelectedEvent(
                metadata = EntityMetadata(),
                namespaceId = NAMESPACE_ID,
                caseId = CASE_ID,
                agentId = agentAId,
                agentName = "AgentA",
            )
        }

        val ex = shouldThrow<AgentInterrupt.Redirect> {
            tool.execute(RedirectTool.Input("AgentA"), context)
        }
        ex.targetAgentName shouldBe "AgentA"
    }

    "detectRedirectLoop ignores selections outside the REDIRECT_LOOP_WINDOW" {
        // REDIRECT_LOOP_WINDOW selections of AgentA, but they are pushed outside the window
        // by REDIRECT_LOOP_WINDOW intervening selections of other agents.
        // Only the last REDIRECT_LOOP_WINDOW events are inspected, so AgentA count = 0 there.
        val tool = RedirectTool(configName = null, eligibleAgents = makeEligible("AgentA", "AgentB"))
        val context = mockk<ToolContext>(relaxed = true)
        val agentAId = UUID.nameUUIDFromBytes("AgentA".toByteArray())
        val agentBId = UUID.nameUUIDFromBytes("AgentB".toByteArray())
        val oldAgentASelections = List(RedirectTool.REDIRECT_LOOP_THRESHOLD) {
            AgentSelectedEvent(metadata = EntityMetadata(), namespaceId = NAMESPACE_ID,
                caseId = CASE_ID, agentId = agentAId, agentName = "AgentA")
        }
        val interveningBSelections = List(RedirectTool.REDIRECT_LOOP_WINDOW) {
            AgentSelectedEvent(metadata = EntityMetadata(), namespaceId = NAMESPACE_ID,
                caseId = CASE_ID, agentId = agentBId, agentName = "AgentB")
        }
        every { context.caseEvents } returns oldAgentASelections + interveningBSelections

        val ex = shouldThrow<AgentInterrupt.Redirect> {
            tool.execute(RedirectTool.Input("AgentA"), context)
        }
        ex.targetAgentName shouldBe "AgentA"
    }

    "detectRedirectLoop is not reset by an agent MessageEvent between selections" {
        // An agent MessageEvent (ActorRole.AGENT) must NOT reset the turn window.
        // AgentA selected THRESHOLD times, separated by an agent message — still a loop.
        val tool = RedirectTool(configName = null, eligibleAgents = makeEligible("AgentA"))
        val context = mockk<ToolContext>(relaxed = true)
        val agentAId = UUID.nameUUIDFromBytes("AgentA".toByteArray())
        every { context.caseEvents } returns listOf(
            AgentSelectedEvent(metadata = EntityMetadata(), namespaceId = NAMESPACE_ID,
                caseId = CASE_ID, agentId = agentAId, agentName = "AgentA"),
            MessageEvent(
                metadata = EntityMetadata(), namespaceId = NAMESPACE_ID, caseId = CASE_ID,
                actor = io.whozoss.agentos.sdk.actor.Actor("agent-1", "Agent", io.whozoss.agentos.sdk.actor.ActorRole.AGENT),
                content = listOf(io.whozoss.agentos.sdk.caseEvent.MessageContent.Text("here is my answer")),
            ),
            AgentSelectedEvent(metadata = EntityMetadata(), namespaceId = NAMESPACE_ID,
                caseId = CASE_ID, agentId = agentAId, agentName = "AgentA"),
        )

        val result = tool.execute(RedirectTool.Input("AgentA"), context)

        result.success shouldBe false
    }

    "detectRedirectLoop does not flag loop when AgentSelectedEvent for target precedes last user MessageEvent" {
        // AgentA was selected THRESHOLD times in a *previous* turn (before the last user message).
        // The current turn has no redirect to AgentA yet, so this must not be flagged.
        val tool = RedirectTool(configName = null, eligibleAgents = makeEligible("AgentA"))
        val context = mockk<ToolContext>(relaxed = true)
        val agentAId = UUID.nameUUIDFromBytes("AgentA".toByteArray())
        every { context.caseEvents } returns List(RedirectTool.REDIRECT_LOOP_THRESHOLD) {
            AgentSelectedEvent(metadata = EntityMetadata(), namespaceId = NAMESPACE_ID,
                caseId = CASE_ID, agentId = agentAId, agentName = "AgentA")
        } + listOf(
            MessageEvent(
                metadata = EntityMetadata(),
                namespaceId = NAMESPACE_ID,
                caseId = CASE_ID,
                actor = io.whozoss.agentos.sdk.actor.Actor("user-1", "User", io.whozoss.agentos.sdk.actor.ActorRole.USER),
                content = listOf(io.whozoss.agentos.sdk.caseEvent.MessageContent.Text("new message")),
            ),
        )

        val ex = shouldThrow<AgentInterrupt.Redirect> {
            tool.execute(RedirectTool.Input("AgentA"), context)
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
