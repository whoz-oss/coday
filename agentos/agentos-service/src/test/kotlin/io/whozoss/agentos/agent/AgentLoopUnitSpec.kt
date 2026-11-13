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
import io.whozoss.agentos.workflow.LoopDataSessionContext
import io.whozoss.agentos.workflow.LoopRunContext
import io.whozoss.agentos.workflow.LoopRunOutcome
import io.whozoss.agentos.workflow.LoopWorkflowRunnerService
import kotlinx.coroutines.flow.toList
import java.util.UUID

/**
 * Unit tests for [AgentLoop].
 *
 * Payload resolution: the [AgentLoop.loopConfig] (set at construction from [AgentConfig.loopConfig]) is either in
 * the legacy `search`/`act` format, used as is, or in the `steps` format, resolved against the `loopData` entry of
 * the session context of the last user message. The text of the message is ignored.
 * These tests cover payload resolution and the mapping of [LoopRunOutcome] to case events.
 * The workflow itself is covered by [LoopWorkflowRunnerUnitSpec], the steps by [LoopStepsTranslatorUnitSpec].
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
            emitted.warnMessage() shouldContain "No loopConfig is configured"
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
        // The steps format, resolved against the loopData of the last user message
        // -------------------------------------------------------------------------

        val scheduledPromptId = UUID.randomUUID()
        val loopData =
            mapOf(
                "searchToolName" to "WHOZ__SearchTalents",
                "searchToolArgs" to mapOf("query" to "kotlin"),
                "agentName" to "Caretaker",
                "prompt" to "Review this profile",
                "scheduledPromptId" to scheduledPromptId.toString(),
            )
        val stepsConfig: JsonNode =
            objectMapper.readTree(
                $$"""
                {"steps": [
                  {"id": "search", "type": "SEARCH", "tool": "${loopData.searchToolName}", "args": "${loopData.searchToolArgs}"},
                  {"id": "start-cases", "type": "START_CASES", "tool": "FreeCaseStarterForUser",
                   "args": {"agentName": "${loopData.agentName}", "prompt": "${loopData.prompt}"},
                   "scheduledPromptId": "${loopData.scheduledPromptId}"}
                ]}
                """.trimIndent(),
            )

        "a steps config is resolved against the loopData of the triggering message and run" {
            val runner = runnerReturning(completed)
            val message = userMessage(sessionContext = LoopDataSessionContext.of(loopData))

            val emitted = agent(runner = runner, loopConfig = stepsConfig).run(listOf(message)).toList()

            val payloadSlot = slot<AgentLoopPayload>()
            coVerify(exactly = 1) { runner.run(capture(payloadSlot), any(), any()) }
            payloadSlot.captured.search.tool shouldBe "WHOZ__SearchTalents"
            payloadSlot.captured.search.params shouldBe objectMapper.readTree("""{"query": "kotlin"}""")
            payloadSlot.captured.act shouldBe
                AgentLoopAct(
                    agentName = "Caretaker",
                    prompt = "Review this profile",
                    title = null,
                    tool = "FreeCaseStarterForUser",
                    scheduledPromptId = scheduledPromptId,
                )
            emitted.filterIsInstance<WarnEvent>() shouldHaveSize 0
        }

        "only the most recent user message provides the loopData" {
            val runner = runnerReturning(completed)
            val older = userMessage(sessionContext = LoopDataSessionContext.of(loopData))
            val newer = userMessage(text = "again")

            val emitted =
                agent(runner = runner, loopConfig = stepsConfig).run(listOf(older, agentMessage("ok"), newer)).toList()

            emitted.warnMessage() shouldContain "requires a 'loopData' entry"
            coVerify(exactly = 0) { runner.run(any(), any(), any()) }
        }

        "a steps config without loopData emits a WarnEvent and does not call the runner" {
            val runner = runnerReturning(completed)
            val message = userMessage(sessionContext = mapOf("preferredLanguage" to "fr"))

            val emitted = agent(runner = runner, loopConfig = stepsConfig).run(listOf(message)).toList()

            emitted shouldHaveSize 3
            emitted.warnMessage() shouldBe
                "AgentLoop 'loop-agent': This agent loop requires a 'loopData' entry in the session context of the " +
                "triggering message."
            emitted.last().shouldBeInstanceOf<AgentFinishedEvent>()
            coVerify(exactly = 0) { runner.run(any(), any(), any()) }
        }

        "a loopData that is not an object emits a WarnEvent and does not call the runner" {
            val runner = runnerReturning(completed)
            val message = userMessage(sessionContext = mapOf("loopData" to "nope"))

            val emitted = agent(runner = runner, loopConfig = stepsConfig).run(listOf(message)).toList()

            emitted.warnMessage() shouldContain "must be an object"
            coVerify(exactly = 0) { runner.run(any(), any(), any()) }
        }

        "a placeholder that cannot be resolved emits a WarnEvent naming it, and does not call the runner" {
            val runner = runnerReturning(completed)
            val message = userMessage(sessionContext = LoopDataSessionContext.of(loopData - "agentName"))

            val emitted = agent(runner = runner, loopConfig = stepsConfig).run(listOf(message)).toList()

            emitted.warnMessage() shouldContain "'loopData.agentName' is not defined in loopData"
            emitted.warnMessage() shouldContain "step 'start-cases'"
            emitted.last().shouldBeInstanceOf<AgentFinishedEvent>()
            coVerify(exactly = 0) { runner.run(any(), any(), any()) }
        }

        "an invalid steps config emits a WarnEvent and does not call the runner" {
            val runner = runnerReturning(completed)
            val onlySearch = objectMapper.readTree("""{"steps": [{"id": "s", "type": "SEARCH", "tool": "S"}]}""")
            val unparseable = objectMapper.readTree("""{"steps": "nope"}""")
            val message = userMessage(sessionContext = LoopDataSessionContext.of(loopData))

            val first = agent(runner = runner, loopConfig = onlySearch).run(listOf(message)).toList()
            val second = agent(runner = runner, loopConfig = unparseable).run(listOf(message)).toList()

            first.warnMessage() shouldContain "exactly two steps"
            second.warnMessage() shouldContain "could not be parsed as valid steps"
            coVerify(exactly = 0) { runner.run(any(), any(), any()) }
        }

        "a legacy loopConfig still works, with or without a loopData on the message" {
            val runner = runnerReturning(completed)

            agent(runner = runner).run(listOf(userMessage())).toList()
            agent(runner = runner).run(listOf(userMessage(sessionContext = LoopDataSessionContext.of(loopData)))).toList()

            coVerify(exactly = 2) { runner.run(match { it.search.tool == "SearchTalents" }, any(), any()) }
        }

        "a loopConfig with a blank search tool emits a WarnEvent and does not call the runner" {
            val runner = runnerReturning(completed)
            val blank =
                objectMapper.readTree(
                    """{"search": {"tool": " ", "params": {}}, "act": {"agentName": "A", "prompt": "p"}}""",
                )

            val emitted = agent(runner = runner, loopConfig = blank).run(listOf(userMessage())).toList()

            emitted.warnMessage() shouldContain "blank search tool"
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
