package io.whozoss.agentos.caseEvent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.SubCaseStartedEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import java.util.UUID

class EffectiveConversationSpec :
    StringSpec({
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()
        val user = Actor(id = "user-1", displayName = "Ada", role = ActorRole.USER)
        val agent = Actor(id = agentId.toString(), displayName = "Agent", role = ActorRole.AGENT)

        fun userMessage(
            text: String,
            rerunOf: UUID? = null,
        ) = MessageEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            actor = user,
            content = listOf(MessageContent.Text(text)),
            rerunOf = rerunOf,
        )

        fun agentMessage(text: String) =
            MessageEvent(namespaceId = namespaceId, caseId = caseId, actor = agent, content = listOf(MessageContent.Text(text)))

        fun selected() = AgentSelectedEvent(namespaceId = namespaceId, caseId = caseId, agentId = agentId, agentName = "agent")

        fun running() = AgentRunningEvent(namespaceId = namespaceId, caseId = caseId, agentId = agentId, agentName = "agent")

        fun finished() = AgentFinishedEvent(namespaceId = namespaceId, caseId = caseId, agentId = agentId, agentName = "agent")

        fun toolRequest() =
            ToolRequestEvent(namespaceId = namespaceId, caseId = caseId, toolRequestId = "t", toolName = "X__y", args = null)

        fun toolResponse() =
            ToolResponseEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                toolRequestId = "t",
                toolName = "X__y",
                output = MessageContent.Text("ok"),
            )

        /**
         * One full turn: the user message, then what the agent did for it. Every call builds new events (new ids):
         * a test builds each turn once and reuses that value in its expectation.
         */
        fun turn(
            message: MessageEvent,
            reply: String,
        ): List<CaseEvent> = listOf(message, selected(), running(), toolRequest(), toolResponse(), agentMessage(reply), finished())

        "a log without any re-run is returned as is" {
            val log = turn(userMessage("one"), "a1") + turn(userMessage("two"), "a2")

            log.effectiveConversation() shouldBeSameInstanceAs log
        }

        "an empty log stays empty" {
            emptyList<CaseEvent>().effectiveConversation() shouldBe emptyList()
        }

        "a re-run replaces the message and drops everything that followed it, orchestration events included" {
            val one = userMessage("one")
            val two = userMessage("two")
            val rerun = userMessage("two, edited", rerunOf = two.id)
            val firstTurn = turn(one, "a1")
            val trailingSelection = selected()
            val log = firstTurn + turn(two, "a2") + listOf(rerun, trailingSelection)

            val effective = log.effectiveConversation()

            effective shouldBe firstTurn + listOf(rerun, trailingSelection)
            effective.none { it.id == two.id } shouldBe true
        }

        "the log itself is untouched: nothing is removed or edited" {
            val two = userMessage("two")
            val log = turn(userMessage("one"), "a1") + turn(two, "a2") + listOf(userMessage("two, edited", rerunOf = two.id))
            val before = log.toList()

            log.effectiveConversation()

            log shouldBe before
        }

        "re-running the first message restarts the conversation from scratch" {
            val one = userMessage("one")
            val rerun = userMessage("one, edited", rerunOf = one.id)
            val log = turn(one, "a1") + turn(userMessage("two"), "a2") + listOf(rerun)

            log.effectiveConversation() shouldBe listOf(rerun)
        }

        "re-running the last message keeps every earlier turn" {
            val one = userMessage("one")
            val two = userMessage("two")
            val rerun = userMessage("two, edited", rerunOf = two.id)
            val firstTurn = turn(one, "a1")
            val laterTurn = turn(userMessage("three"), "a3")
            val log = firstTurn + turn(two, "a2") + listOf(rerun) + laterTurn

            log.effectiveConversation() shouldBe firstTurn + listOf(rerun) + laterTurn
        }

        "chained re-runs: re-running a message that was itself a re-run" {
            val one = userMessage("one")
            val two = userMessage("two")
            val first = userMessage("two, v2", rerunOf = two.id)
            val second = userMessage("two, v3", rerunOf = first.id)
            val firstTurn = turn(one, "a1")
            val log = firstTurn + turn(two, "a2") + turn(first, "a2 v2") + listOf(second)

            log.effectiveConversation() shouldBe firstTurn + listOf(second)
        }

        "a re-run of an earlier message also cuts the later re-runs" {
            val one = userMessage("one")
            val two = userMessage("two")
            val twoV2 = userMessage("two, v2", rerunOf = two.id)
            val oneV2 = userMessage("one, v2", rerunOf = one.id)
            val log = turn(one, "a1") + turn(two, "a2") + turn(twoV2, "a2 v2") + listOf(oneV2)

            log.effectiveConversation() shouldBe listOf(oneV2)
        }

        "a target that is not in the conversation is ignored: the message is simply added" {
            val firstTurn = turn(userMessage("one"), "a1")
            val unknown = userMessage("edited", rerunOf = UUID.randomUUID())
            val log = firstTurn + listOf(unknown)

            log.effectiveConversation() shouldBe firstTurn + listOf(unknown)
        }

        "a target already cut by an earlier re-run is ignored too, it does not cut again" {
            val one = userMessage("one")
            val two = userMessage("two")
            val first = userMessage("two, v2", rerunOf = two.id)
            val stale = userMessage("two, v3", rerunOf = two.id)
            val firstTurn = turn(one, "a1")
            val log = firstTurn + turn(two, "a2") + listOf(first) + listOf(stale)

            log.effectiveConversation() shouldBe firstTurn + listOf(first, stale)
        }

        "sub-case observations and questions after the replaced message are excluded" {
            val one = userMessage("one")
            val subCase =
                SubCaseStartedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    delegationId = UUID.randomUUID(),
                    toolRequestId = "t",
                    subCaseId = UUID.randomUUID(),
                    agentName = "sub",
                    task = "do",
                    resumed = false,
                )
            val question =
                QuestionEvent(namespaceId = namespaceId, caseId = caseId, agentId = agentId, agentName = "agent", question = "?")
            val rerun = userMessage("one, edited", rerunOf = one.id)
            val log = listOf(one, subCase, question, rerun)

            log.effectiveConversation() shouldBe listOf(rerun)
        }

        "only a user message can re-run: an agent message carrying a rerunOf is not interpreted" {
            val one = userMessage("one")
            val odd = agentMessage("reply").copy(rerunOf = one.id)
            val log = listOf(one, odd)

            log.effectiveConversation() shouldBeSameInstanceAs log
        }
    })
