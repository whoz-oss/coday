package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.user.User
import io.whozoss.agentos.workflow.AgentLoopAct
import io.whozoss.agentos.workflow.AgentLoopPayload
import io.whozoss.agentos.workflow.AgentLoopSearch
import io.whozoss.agentos.workflow.AgentLoopSessionContext
import io.whozoss.agentos.workflow.LoopRunContext
import io.whozoss.agentos.workflow.LoopRunOutcome
import io.whozoss.agentos.workflow.LoopWorkflowRunnerService
import kotlinx.coroutines.flow.toList
import java.util.UUID

/**
 * Unit tests for [AgentLoop].
 *
 * Payload resolution: the `agentLoop` entry of the session context of the last user message takes
 * precedence, [AgentLoop.loopConfig] (set at construction from [AgentConfig.loopConfig]) is the fallback.
 * The text of the message is ignored.
 * These tests cover payload resolution and the mapping of [LoopRunOutcome] to case events.
 * The workflow itself is covered by [LoopWorkflowRunnerUnitSpec].
 */
class AgentLoopUnitSpec :
    StringSpec({

        val objectMapper = ObjectMapper().registerKotlinModule()
        val namespaceId: UUID = UUID.randomUUID()
        val caseId: UUID = UUID.randomUUID()

        val validPayloadJson =
            """
            {
                "search": {
                    "tool": "SearchTalents",
                    "params": {"talentId": ["6ac76692a909eaaae0b078d6"], "resolveTargets": ["MANAGER"], "next": null}
                },
                "act": {"agentName": "ProfileCaretaker", "promptTemplate": "This talent has not completed their profile. As their manager, I need you to help me review and improve it."}
            }
            """.trimIndent()

        val validLoopConfig: JsonNode = objectMapper.readTree(validPayloadJson)

        val completed =
            LoopRunOutcome.Completed(
                searchTool = "SearchTalents",
                returned = 1,
                totalCount = 1,
                hasMorePages = false,
                startedCaseIds = listOf(UUID.randomUUID()),
                unknownUser = 0,
                noAgentAccess = 0,
                failed = 0,
                overLimit = 0,
                maxItems = 50,
                targetAgent = "ProfileCaretaker",
                interrupted = false,
            )

        // -------------------------------------------------------------------------
        // Helpers
        // -------------------------------------------------------------------------

        fun runnerReturning(outcome: LoopRunOutcome): LoopWorkflowRunnerService =
            mockk<LoopWorkflowRunnerService>().also { coEvery { it.run(any(), any(), any()) } returns outcome }

        fun agent(
            runner: LoopWorkflowRunnerService = runnerReturning(completed),
            name: String = "loop-agent",
            resolvedTools: Collection<StandardTool<*>> = emptyList(),
            triggerUser: User? = null,
            loopConfig: JsonNode? = validLoopConfig,
        ) = AgentLoop(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            name = name,
            objectMapper = objectMapper,
            runner = runner,
            resolvedTools = resolvedTools,
            triggerUser = triggerUser,
            loopConfig = loopConfig,
        )

        fun userMessage(
            text: String = "start",
            sessionContext: Map<String, Any?>? = null,
        ) =
            MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = Actor(id = "user-1", displayName = "Alice", role = ActorRole.USER),
                content = listOf(MessageContent.Text(text)),
                sessionContext = sessionContext,
            )

        fun agentMessage(text: String) =
            MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = Actor(id = "agent-1", displayName = "loop-agent", role = ActorRole.AGENT),
                content = listOf(MessageContent.Text(text)),
            )

        fun List<CaseEvent>.warnMessage(): String = filterIsInstance<WarnEvent>().single().message

        // -------------------------------------------------------------------------
        // Completed outcome
        // -------------------------------------------------------------------------

        "completed run emits AgentRunningEvent, summary MessageEvent then AgentFinishedEvent" {
            val emitted = agent().run(listOf(userMessage())).toList()

            emitted shouldHaveSize 3
            emitted[0].shouldBeInstanceOf<AgentRunningEvent>()
            val summary = emitted[1].shouldBeInstanceOf<MessageEvent>()
            summary.actor.role shouldBe ActorRole.AGENT
            (summary.content.single() as MessageContent.Text).content shouldContain "Started 1 case(s)"
            emitted[2].shouldBeInstanceOf<AgentFinishedEvent>()
        }

        "payload from loopConfig is passed to the runner with the case context" {
            val runner = runnerReturning(completed)
            val user =
                User(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    externalId = "ext-trigger",
                    email = "t@example.com",
                )
            val payloadSlot = slot<AgentLoopPayload>()
            val contextSlot = slot<LoopRunContext>()

            agent(runner = runner, name = "my-loop", triggerUser = user)
                .run(listOf(userMessage()))
                .toList()

            coVerify { runner.run(capture(payloadSlot), capture(contextSlot), any()) }
            payloadSlot.captured.search.tool shouldBe "SearchTalents"
            payloadSlot.captured.act shouldBe
                AgentLoopAct(
                    "ProfileCaretaker",
                    "This talent has not completed their profile. As their manager, I need you to help me review and improve it.",
                )
            contextSlot.captured.namespaceId shouldBe namespaceId
            contextSlot.captured.caseId shouldBe caseId
            contextSlot.captured.agentName shouldBe "my-loop"
            contextSlot.captured.triggerUser shouldBe user
        }

        "unknown fields in loopConfig are ignored" {
            val runner = runnerReturning(completed)
            val withExtraFields =
                objectMapper.readTree(
                    """
                    {"search": {"tool": "SearchTalents", "params": {}, "version": 0},
                     "act": {"agentName": "ProfileCaretaker", "promptTemplate": "This talent has not completed their profile. As their manager, I need you to help me review and improve it.", "note": "x"}}
                    """.trimIndent(),
                )

            agent(runner = runner, loopConfig = withExtraFields).run(listOf(userMessage())).toList()

            coVerify(
                exactly = 1,
            ) {
                runner.run(
                    match { it.search.tool == "SearchTalents" && it.act.agentName == "ProfileCaretaker" },
                    any(),
                    any(),
                )
            }
        }

        // -------------------------------------------------------------------------
        // Aborted outcome
        // -------------------------------------------------------------------------

        "aborted run emits a WarnEvent carrying the reason" {
            val emitted =
                agent(runner = runnerReturning(LoopRunOutcome.Aborted("Search tool 'X' is not available")))
                    .run(listOf(userMessage()))
                    .toList()

            emitted shouldHaveSize 3
            emitted[0].shouldBeInstanceOf<AgentRunningEvent>()
            emitted.warnMessage() shouldContain "Search tool 'X' is not available"
            emitted[2].shouldBeInstanceOf<AgentFinishedEvent>()
        }

        // -------------------------------------------------------------------------
        // Missing / invalid loopConfig
        // -------------------------------------------------------------------------

        "no loopConfig emits a WarnEvent and does not call the runner" {
            val runner = runnerReturning(completed)

            val emitted = agent(runner = runner, loopConfig = null).run(listOf(userMessage())).toList()

            emitted shouldHaveSize 3
            emitted[0].shouldBeInstanceOf<AgentRunningEvent>()
            emitted[1].shouldBeInstanceOf<WarnEvent>()
            emitted.warnMessage() shouldContain "No loop payload"
            emitted[2].shouldBeInstanceOf<AgentFinishedEvent>()
            coVerify(exactly = 0) { runner.run(any(), any(), any()) }
        }

        "invalid loopConfig emits a WarnEvent and does not call the runner" {
            val runner = runnerReturning(completed)
            val invalid = objectMapper.readTree("""{"note": "not a payload"}""")

            val emitted = agent(runner = runner, loopConfig = invalid).run(listOf(userMessage())).toList()

            emitted shouldHaveSize 3
            emitted[0].shouldBeInstanceOf<AgentRunningEvent>()
            emitted[1].shouldBeInstanceOf<WarnEvent>()
            emitted[2].shouldBeInstanceOf<AgentFinishedEvent>()
            coVerify(exactly = 0) { runner.run(any(), any(), any()) }
        }

        // -------------------------------------------------------------------------
        // Payload from the session context of the last user message
        // -------------------------------------------------------------------------

        val sessionPayload =
            AgentLoopPayload(
                search = AgentLoopSearch(tool = "WHOZ__SearchTalents", params = objectMapper.readTree("""{"query":"kotlin"}""")),
                act = AgentLoopAct(agentName = "Caretaker", prompt = "Review this profile"),
            )

        "session context payload is used, and takes precedence over loopConfig" {
            val runner = runnerReturning(completed)
            val message = userMessage(sessionContext = AgentLoopSessionContext.of(sessionPayload, objectMapper))

            val emitted = agent(runner = runner, loopConfig = validLoopConfig).run(listOf(message)).toList()

            val payloadSlot = slot<AgentLoopPayload>()
            coVerify(exactly = 1) { runner.run(capture(payloadSlot), any(), any()) }
            payloadSlot.captured shouldBe sessionPayload
            emitted.filterIsInstance<WarnEvent>() shouldHaveSize 0
        }

        "session context payload is used when no loopConfig is configured" {
            val runner = runnerReturning(completed)
            val message = userMessage(sessionContext = AgentLoopSessionContext.of(sessionPayload, objectMapper))

            agent(runner = runner, loopConfig = null).run(listOf(message)).toList()

            coVerify(exactly = 1) { runner.run(sessionPayload, any(), any()) }
        }

        "only the most recent user message is considered" {
            val runner = runnerReturning(completed)
            val older = userMessage(sessionContext = AgentLoopSessionContext.of(sessionPayload, objectMapper))
            val newer = userMessage(text = "again")

            agent(runner = runner, loopConfig = validLoopConfig).run(listOf(older, agentMessage("ok"), newer)).toList()

            // The newer user message has no agentLoop entry: fallback to loopConfig, not to the older message.
            coVerify(exactly = 1) { runner.run(match { it.search.tool == "SearchTalents" }, any(), any()) }
        }

        "falls back to loopConfig when the last user message has no agentLoop entry" {
            val runner = runnerReturning(completed)
            val message = userMessage(sessionContext = mapOf("preferredLanguage" to "fr"))

            agent(runner = runner, loopConfig = validLoopConfig).run(listOf(message)).toList()

            coVerify(exactly = 1) { runner.run(match { it.search.tool == "SearchTalents" }, any(), any()) }
        }

        "neither session context nor loopConfig emits a WarnEvent and does not call the runner" {
            val runner = runnerReturning(completed)
            val message = userMessage(sessionContext = mapOf("preferredLanguage" to "fr"))

            val emitted = agent(runner = runner, loopConfig = null).run(listOf(message)).toList()

            emitted.warnMessage() shouldContain "No loop payload"
            emitted.last().shouldBeInstanceOf<AgentFinishedEvent>()
            coVerify(exactly = 0) { runner.run(any(), any(), any()) }
        }

        "malformed agentLoop entry emits a WarnEvent and does not call the runner, even with a loopConfig" {
            val runner = runnerReturning(completed)
            val message = userMessage(sessionContext = mapOf("agentLoop" to mapOf("search" to "not-an-object")))

            val emitted = agent(runner = runner, loopConfig = validLoopConfig).run(listOf(message)).toList()

            emitted.warnMessage() shouldContain "agentLoop"
            emitted.last().shouldBeInstanceOf<AgentFinishedEvent>()
            coVerify(exactly = 0) { runner.run(any(), any(), any()) }
        }

        "blank search.tool in the session context payload emits a WarnEvent and does not call the runner" {
            val runner = runnerReturning(completed)
            val blank = sessionPayload.copy(search = sessionPayload.search.copy(tool = " "))
            val message = userMessage(sessionContext = AgentLoopSessionContext.of(blank, objectMapper))

            val emitted = agent(runner = runner).run(listOf(message)).toList()

            emitted.warnMessage() shouldContain "blank 'search.tool'"
            coVerify(exactly = 0) { runner.run(any(), any(), any()) }
        }

        // -------------------------------------------------------------------------
        // Interruption and invariants
        // -------------------------------------------------------------------------

        "shouldContinue=false emits only AgentFinishedEvent and does not call the runner" {
            val runner = runnerReturning(completed)

            val emitted = agent(runner = runner).run(listOf(userMessage()), shouldContinue = { false }).toList()

            emitted shouldHaveSize 1
            emitted[0].shouldBeInstanceOf<AgentFinishedEvent>()
            coVerify(exactly = 0) { runner.run(any(), any(), any()) }
        }

        "empty event list throws IllegalArgumentException" {
            shouldThrow<IllegalArgumentException> {
                agent().run(emptyList()).toList()
            }
        }

        "AgentFinishedEvent carries agent identity and no llmProvider" {
            val loop = agent(name = "my-loop")

            val finished = loop.run(listOf(userMessage())).toList().last() as AgentFinishedEvent

            finished.agentName shouldBe "my-loop"
            finished.agentId shouldBe loop.id
            finished.llmProvider shouldBe null
            finished.llmModel shouldBe null
        }

        "all events carry the case namespaceId and caseId" {
            agent().run(listOf(userMessage())).toList().forEach { event ->
                event.namespaceId shouldBe namespaceId
                event.caseId shouldBe caseId
            }
        }

        "AgentRunningEvent carries null llmProvider and llmModel" {
            val running = agent().run(listOf(userMessage())).toList().first() as AgentRunningEvent

            running.llmProvider shouldBe null
            running.llmModel shouldBe null
        }

        "llmProvider and llmModel are sentinel values signalling no LLM usage" {
            val loop = agent()

            loop.llmProvider shouldBe AgentLoop.PROVIDER_NAME
            loop.llmModel shouldBe AgentLoop.MODEL_NAME
        }
    })
