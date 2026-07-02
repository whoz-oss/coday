package io.whozoss.agentos.delegation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.caseFlow.CaseRuntime
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.tool.ToolContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

class DelegationToolUnitSpec :
    StringSpec({
        timeout = 5_000

        val parentCaseId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val subCaseId = UUID.randomUUID()
        val allowedAgents = listOf("sub-agent", "researcher")

        val toolContext =
            ToolContext(
                namespaceId = namespaceId,
                userId = userId,
                userExternalId = null,
                caseEvents = emptyList(),
            )

        fun agentMessage(text: String) =
            MessageEvent(
                namespaceId = namespaceId,
                caseId = subCaseId,
                actor = Actor(id = UUID.randomUUID().toString(), displayName = "sub-agent", role = ActorRole.AGENT),
                content = listOf(MessageContent.Text(text)),
            )

        fun questionEvent(
            question: String,
            options: List<String>? = null,
        ) = QuestionEvent(
            namespaceId = namespaceId,
            caseId = subCaseId,
            agentId = UUID.randomUUID(),
            agentName = "sub-agent",
            question = question,
            options = options,
        )

        fun makeTool(
            launcher: SubCaseManager,
            events: List<io.whozoss.agentos.sdk.caseEvent.CaseEvent> = emptyList(),
            timeoutMs: Long = 2_000,
            loadCaseEvents: suspend (UUID) -> List<io.whozoss.agentos.sdk.caseEvent.CaseEvent> = { events },
            eventLoadTimeoutMs: Long = 2_000,
        ) = DelegationTool(
            subCaseManager = launcher,
            parentCaseId = parentCaseId,
            namespaceId = namespaceId,
            allowedAgents = allowedAgents,
            loadCaseEvents = loadCaseEvents,
            timeoutMs = timeoutMs,
            eventLoadTimeoutMs = eventLoadTimeoutMs,
        )

        fun idleRuntime(id: UUID = subCaseId): CaseRuntime {
            val runtime = mockk<CaseRuntime>()
            every { runtime.id } returns id
            every { runtime.statusFlow } returns MutableStateFlow(CaseStatus.IDLE)
            return runtime
        }

        fun singleDelegation(
            agentName: String = "sub-agent",
            task: String = "do X",
            subCaseId: UUID? = null,
        ) = DelegationTool.Args(delegations = listOf(DelegationTool.Delegation(agentName, task, subCaseId)))

        // -------------------------------------------------------------------------
        // Input validation
        // -------------------------------------------------------------------------

        "returns failure when delegations list is empty" {
            val tool = makeTool(mockk())
            val result = tool.execute(DelegationTool.Args(delegations = emptyList()), toolContext)
            result.success shouldBe false
        }

        "returns failure when input is null" {
            val tool = makeTool(mockk())
            val result = tool.execute(null, toolContext)
            result.success shouldBe false
        }

        "returns failure when agentName is not in allowlist" {
            val tool = makeTool(mockk())
            val result = tool.execute(singleDelegation(agentName = "unknown-agent"), toolContext)
            result.success shouldBe false
        }

        "returns failure when userId is null in context" {
            val tool = makeTool(mockk())
            val result = tool.execute(singleDelegation(), toolContext.copy(userId = null))
            result.success shouldBe false
        }

        // -------------------------------------------------------------------------
        // Single delegation — nominal path
        // -------------------------------------------------------------------------

        "returns last agent message in array when sub-case reaches IDLE" {
            val launcher = mockk<SubCaseManager>()
            val runtime = idleRuntime()
            val events = listOf(agentMessage("the result"))
            val tool = makeTool(launcher, events)

            every { launcher.startSubCase(parentCaseId, namespaceId, "sub-agent", "do X", userId) } returns runtime

            val result = tool.execute(singleDelegation(), toolContext)

            result.success shouldBe true
            val tree = jacksonObjectMapper().readTree(result.output)
            tree.isArray shouldBe true
            tree[0].get("success").asBoolean() shouldBe true
            tree[0].get("result").asText() shouldBe "the result"
            tree[0].get("agentName").asText() shouldBe "sub-agent"
            tree[0].get("subCaseId").asText() shouldBe subCaseId.toString()
        }

        "picks the last agent message when history contains multiple" {
            val launcher = mockk<SubCaseManager>()
            val runtime = idleRuntime()
            val events = listOf(agentMessage("first answer"), agentMessage("final answer"))
            val tool = makeTool(launcher, events)

            every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns runtime

            val result = tool.execute(singleDelegation(), toolContext)

            val tree = jacksonObjectMapper().readTree(result.output)
            tree[0].get("result").asText() shouldBe "final answer"
        }

        "returns fallback message when sub-case produces no agent message" {
            val launcher = mockk<SubCaseManager>()
            val runtime = idleRuntime()
            val tool = makeTool(launcher, events = emptyList())

            every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns runtime

            val result = tool.execute(singleDelegation(), toolContext)

            result.success shouldBe true
            val tree = jacksonObjectMapper().readTree(result.output)
            tree[0].get("result").asText() shouldBe "Sub-agent completed the task but produced no text output."
        }

        // -------------------------------------------------------------------------
        // Parallel delegations
        // -------------------------------------------------------------------------

        "runs two delegations in parallel and returns both results" {
            val subCaseId2 = UUID.randomUUID()
            val launcher = mockk<SubCaseManager>()
            val runtime1 = idleRuntime(subCaseId)
            val runtime2 = idleRuntime(subCaseId2)

            every { launcher.startSubCase(parentCaseId, namespaceId, "sub-agent", "task 1", userId) } returns runtime1
            every { launcher.startSubCase(parentCaseId, namespaceId, "researcher", "task 2", userId) } returns runtime2

            val events1 = listOf(agentMessage("result 1"))
            val events2 =
                listOf(
                    MessageEvent(
                        namespaceId = namespaceId,
                        caseId = subCaseId2,
                        actor = Actor(id = UUID.randomUUID().toString(), displayName = "researcher", role = ActorRole.AGENT),
                        content = listOf(MessageContent.Text("result 2")),
                    ),
                )

            val tool =
                DelegationTool(
                    subCaseManager = launcher,
                    parentCaseId = parentCaseId,
                    namespaceId = namespaceId,
                    allowedAgents = allowedAgents,
                    loadCaseEvents = { id -> if (id == subCaseId) events1 else events2 },
                    timeoutMs = 2_000,
                )

            val args =
                DelegationTool.Args(
                    delegations =
                        listOf(
                            DelegationTool.Delegation("sub-agent", "task 1"),
                            DelegationTool.Delegation("researcher", "task 2"),
                        ),
                )

            val result = tool.execute(args, toolContext)

            result.success shouldBe true
            val tree = jacksonObjectMapper().readTree(result.output)
            tree.isArray shouldBe true
            tree.size() shouldBe 2
            val byAgent = (0 until tree.size()).associate { tree[it].get("agentName").asText() to tree[it] }
            byAgent["sub-agent"]!!.get("result").asText() shouldBe "result 1"
            byAgent["researcher"]!!.get("result").asText() shouldBe "result 2"
        }

        "overall success is true when at least one delegation succeeds" {
            val subCaseId2 = UUID.randomUUID()
            val launcher = mockk<SubCaseManager>()
            val successRuntime = idleRuntime(subCaseId)
            val errorRuntime = mockk<CaseRuntime>()
            every { errorRuntime.id } returns subCaseId2
            every { errorRuntime.statusFlow } returns MutableStateFlow(CaseStatus.ERROR)

            every { launcher.startSubCase(parentCaseId, namespaceId, "sub-agent", "task 1", userId) } returns successRuntime
            every { launcher.startSubCase(parentCaseId, namespaceId, "researcher", "task 2", userId) } returns errorRuntime

            val tool =
                DelegationTool(
                    subCaseManager = launcher,
                    parentCaseId = parentCaseId,
                    namespaceId = namespaceId,
                    allowedAgents = allowedAgents,
                    loadCaseEvents = { listOf(agentMessage("ok")) },
                    timeoutMs = 2_000,
                )

            val args =
                DelegationTool.Args(
                    delegations =
                        listOf(
                            DelegationTool.Delegation("sub-agent", "task 1"),
                            DelegationTool.Delegation("researcher", "task 2"),
                        ),
                )

            val result = tool.execute(args, toolContext)

            result.success shouldBe true // at least one succeeded
            val tree = jacksonObjectMapper().readTree(result.output)
            val byAgent = (0 until tree.size()).associate { tree[it].get("agentName").asText() to tree[it] }
            byAgent["sub-agent"]!!.get("success").asBoolean() shouldBe true
            byAgent["researcher"]!!.get("success").asBoolean() shouldBe false
            byAgent["researcher"]!!.has("error") shouldBe true
        }

        "overall success is false when all delegations fail" {
            val launcher = mockk<SubCaseManager>()
            val errorRuntime = mockk<CaseRuntime>()
            every { errorRuntime.id } returns subCaseId
            every { errorRuntime.statusFlow } returns MutableStateFlow(CaseStatus.ERROR)

            every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns errorRuntime

            val tool = makeTool(launcher)
            val result = tool.execute(singleDelegation(), toolContext)

            result.success shouldBe false
            val tree = jacksonObjectMapper().readTree(result.output)
            tree[0].get("success").asBoolean() shouldBe false
        }

        // -------------------------------------------------------------------------
        // QuestionEvent paths
        // -------------------------------------------------------------------------

        "returns pendingQuestion when sub-case reaches IDLE after a QuestionEvent" {
            val launcher = mockk<SubCaseManager>()
            val runtime = idleRuntime()
            val events = listOf(questionEvent("What is the target environment?", listOf("prod", "staging")))
            val tool = makeTool(launcher, events)

            every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns runtime

            val result = tool.execute(singleDelegation(), toolContext)

            result.success shouldBe true
            val tree = jacksonObjectMapper().readTree(result.output)
            tree[0].get("pendingQuestion").asText() shouldBe "What is the target environment?"
            tree[0].get("subCaseId").asText() shouldBe subCaseId.toString()
        }

        "returns normal result when QuestionEvent is followed by an agent message" {
            val launcher = mockk<SubCaseManager>()
            val runtime = idleRuntime()
            val events = listOf(questionEvent("Clarify?"), agentMessage("I resolved it myself"))
            val tool = makeTool(launcher, events)

            every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns runtime

            val result = tool.execute(singleDelegation(), toolContext)

            result.success shouldBe true
            val tree = jacksonObjectMapper().readTree(result.output)
            tree[0].get("result").asText() shouldBe "I resolved it myself"
        }

        // -------------------------------------------------------------------------
        // Resume path
        // -------------------------------------------------------------------------

        "routes to resumeSubCase when subCaseId is provided" {
            val launcher = mockk<SubCaseManager>()
            val runtime = idleRuntime()
            val events = listOf(agentMessage("resumed result"))
            val tool = makeTool(launcher, events)

            every { launcher.resumeSubCase(subCaseId, "sub-agent", "follow-up", userId, allowedAgents) } returns runtime

            val result =
                tool.execute(
                    DelegationTool.Args(
                        delegations = listOf(DelegationTool.Delegation("sub-agent", "follow-up", subCaseId)),
                    ),
                    toolContext,
                )

            result.success shouldBe true
            val tree = jacksonObjectMapper().readTree(result.output)
            tree[0].get("result").asText() shouldBe "resumed result"
            verify(exactly = 0) { launcher.startSubCase(any(), any(), any(), any(), any()) }
        }

        // -------------------------------------------------------------------------
        // Timeout paths
        // -------------------------------------------------------------------------

        "returns failure when event loading exceeds eventLoadTimeoutMs" {
            val launcher = mockk<SubCaseManager>()
            val runtime = idleRuntime()
            val tool =
                makeTool(
                    launcher = launcher,
                    loadCaseEvents = {
                        delay(Long.MAX_VALUE)
                        emptyList()
                    },
                    eventLoadTimeoutMs = 200,
                )

            every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns runtime

            val result = tool.execute(singleDelegation(), toolContext)

            result.success shouldBe false
        }

        "returns failure and kills sub-case when batch timeout is reached" {
            val launcher = mockk<SubCaseManager>()
            val runtime = mockk<CaseRuntime>()
            every { runtime.id } returns subCaseId
            every { runtime.statusFlow } returns MutableStateFlow(CaseStatus.RUNNING)
            every { launcher.killCase(subCaseId) } returns Unit
            val tool = makeTool(launcher, timeoutMs = 200)

            every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns runtime

            val result = tool.execute(singleDelegation(), toolContext)

            result.success shouldBe false
        }
    })
