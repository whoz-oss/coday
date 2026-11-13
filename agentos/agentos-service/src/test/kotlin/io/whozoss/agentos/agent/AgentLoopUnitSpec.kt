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
import io.whozoss.agentos.caseFlow.SessionContextKeys
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
import io.whozoss.agentos.workflow.LoopRunContext
import io.whozoss.agentos.workflow.LoopRunOutcome
import io.whozoss.agentos.workflow.LoopWorkflowRunnerService
import kotlinx.coroutines.flow.toList
import java.util.UUID

/**
 * Unit tests for [AgentLoop].
 *
 * AgentLoop reads its payload from the `agentFlowConfig` of the last user message's session context when there
 * is one, and from [AgentLoop.loopConfig] (set at construction from [AgentConfig.loopConfig]) otherwise.
 * Message content is ignored for payload resolution.
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
        ) = MessageEvent(
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
            emitted.warnMessage() shouldContain "No agentFlowConfig in the context"
            emitted.warnMessage() shouldContain "no loopConfig"
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
        // agentFlowConfig in the context of the triggering message
        // -------------------------------------------------------------------------

        fun contextPayload(
            tool: String = "searchAgentUsers",
            agentName: String = "ContextAgent",
        ): Map<String, Any?> =
            mapOf(
                "search" to mapOf("tool" to tool, "params" to mapOf("agentConfigId" to "abc")),
                "act" to mapOf("agentName" to agentName, "prompt" to "Hello from the context"),
            )

        "agentFlowConfig in the last user message context takes precedence over loopConfig" {
            val runner = runnerReturning(completed)
            val payloadSlot = slot<AgentLoopPayload>()
            val context = mapOf(SessionContextKeys.AGENT_FLOW_CONFIG to contextPayload())

            agent(runner = runner).run(listOf(userMessage(sessionContext = context))).toList()

            coVerify { runner.run(capture(payloadSlot), any(), any()) }
            payloadSlot.captured.search.tool shouldBe "searchAgentUsers"
            payloadSlot.captured.search.params shouldBe objectMapper.readTree("""{"agentConfigId": "abc"}""")
            payloadSlot.captured.act shouldBe AgentLoopAct("ContextAgent", "Hello from the context")
        }

        "agentFlowConfig of the context is used when there is no loopConfig" {
            val runner = runnerReturning(completed)
            val context = mapOf(SessionContextKeys.AGENT_FLOW_CONFIG to contextPayload())

            val emitted =
                agent(runner = runner, loopConfig = null).run(listOf(userMessage(sessionContext = context))).toList()

            emitted.filterIsInstance<WarnEvent>() shouldHaveSize 0
            coVerify(exactly = 1) { runner.run(match { it.act.agentName == "ContextAgent" }, any(), any()) }
        }

        "an invalid agentFlowConfig in the context aborts with a WarnEvent, even with a valid loopConfig" {
            val runner = runnerReturning(completed)
            val context = mapOf(SessionContextKeys.AGENT_FLOW_CONFIG to mapOf("note" to "not a payload"))

            val emitted = agent(runner = runner).run(listOf(userMessage(sessionContext = context))).toList()

            emitted shouldHaveSize 3
            emitted.warnMessage() shouldContain "agentFlowConfig of the triggering message"
            coVerify(exactly = 0) { runner.run(any(), any(), any()) }
        }

        "a blank search tool or act agentName in the context aborts with a WarnEvent" {
            val runner = runnerReturning(completed)

            val blankTool = mapOf(SessionContextKeys.AGENT_FLOW_CONFIG to contextPayload(tool = " "))
            agent(runner = runner).run(listOf(userMessage(sessionContext = blankTool))).toList().warnMessage() shouldContain
                "search.tool"

            val blankAgent = mapOf(SessionContextKeys.AGENT_FLOW_CONFIG to contextPayload(agentName = ""))
            agent(runner = runner).run(listOf(userMessage(sessionContext = blankAgent))).toList().warnMessage() shouldContain
                "act.agentName"

            coVerify(exactly = 0) { runner.run(any(), any(), any()) }
        }

        "agentFlowConfig given as nested maps, as after persistence, is parsed" {
            val runner = runnerReturning(completed)
            // what a case event reloaded from the store holds: plain maps and lists all the way down
            @Suppress("UNCHECKED_CAST")
            val persisted =
                objectMapper.readValue(
                    objectMapper.writeValueAsString(mapOf(SessionContextKeys.AGENT_FLOW_CONFIG to contextPayload())),
                    Map::class.java,
                ) as Map<String, Any?>

            agent(runner = runner, loopConfig = null).run(listOf(userMessage(sessionContext = persisted))).toList()

            coVerify(exactly = 1) { runner.run(match { it.search.tool == "searchAgentUsers" }, any(), any()) }
        }

        "only the last user message is read for agentFlowConfig" {
            val runner = runnerReturning(completed)
            val older = userMessage(sessionContext = mapOf(SessionContextKeys.AGENT_FLOW_CONFIG to contextPayload(agentName = "Older")))
            val last = userMessage(sessionContext = mapOf(SessionContextKeys.AGENT_FLOW_CONFIG to contextPayload(agentName = "Last")))

            agent(runner = runner).run(listOf(older, agentMessage("ok"), last)).toList()

            coVerify(exactly = 1) { runner.run(match { it.act.agentName == "Last" }, any(), any()) }
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
