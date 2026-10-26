package io.whozoss.agentos.delegation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.caseFlow.CaseRuntime
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.tool.ToolContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
                toolRequestId = "parent-tool-request",
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
            subCaseManager = launcher.also {
                // DelegationTool records durable lifecycle observations on the parent case.
                // Keep SubCaseManager strict for all business interactions while allowing
                // this side effect in tests that do not assert on the emitted events.
                every { it.emitParentEvent(any()) } returns Unit
            },
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
            every { runtime.activityCount } returns 0L
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
            every { launcher.isCostPaused(any()) } returns false
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
            every { launcher.isCostPaused(any()) } returns false
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
            every { launcher.isCostPaused(any()) } returns false
            val runtime = idleRuntime()
            val tool = makeTool(launcher, events = emptyList())

            every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns runtime

            val result = tool.execute(singleDelegation(), toolContext)

            result.success shouldBe true
            val tree = jacksonObjectMapper().readTree(result.output)
            tree[0].get("result").asText() shouldBe "Sub-agent completed the task but produced no text output."
        }

        "reports the iteration limit the sub-agent reached with its summary as a partial result" {
            val launcher = mockk<SubCaseManager>()
            every { launcher.isCostPaused(any()) } returns false
            fun finished(limit: Int?) =
                AgentFinishedEvent(namespaceId = namespaceId, caseId = subCaseId, agentId = UUID.randomUUID(),
                    agentName = "sub-agent", maxIterationsReached = limit)
            // An earlier turn stopped at the limit too: only the latest run counts.
            val events = listOf(agentMessage("old summary"), finished(20), agentMessage("summary so far"), finished(20))
            val tool = makeTool(launcher, events)
            every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns idleRuntime()

            val tree = jacksonObjectMapper().readTree(tool.execute(singleDelegation(), toolContext).output)

            tree[0].get("success").asBoolean() shouldBe true
            tree[0].get("result").asText() shouldBe "summary so far"
            tree[0].get("maxIterationsReached").asInt() shouldBe 20
            tree[0].get("subCaseId").asText() shouldBe subCaseId.toString()
            tool.description shouldContain "maxIterationsReached"

            val completed = makeTool(launcher, listOf(agentMessage("old summary"), finished(20), agentMessage("done"), finished(null)))
            jacksonObjectMapper().readTree(completed.execute(singleDelegation(), toolContext).output)[0].has("maxIterationsReached") shouldBe false
        }

        // -------------------------------------------------------------------------
        // Parallel delegations
        // -------------------------------------------------------------------------

        "runs two delegations in parallel and returns both results" {
            val subCaseId2 = UUID.randomUUID()
            val launcher = mockk<SubCaseManager>()
            every { launcher.isCostPaused(any()) } returns false
            every { launcher.emitParentEvent(any()) } returns Unit
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
                makeTool(
                    launcher = launcher,
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
            every { launcher.isCostPaused(any()) } returns false
            every { launcher.emitParentEvent(any()) } returns Unit
            val successRuntime = idleRuntime(subCaseId)
            val errorRuntime = mockk<CaseRuntime>()
            every { errorRuntime.id } returns subCaseId2
            every { errorRuntime.statusFlow } returns MutableStateFlow(CaseStatus.ERROR)

            every { launcher.startSubCase(parentCaseId, namespaceId, "sub-agent", "task 1", userId) } returns successRuntime
            every { launcher.startSubCase(parentCaseId, namespaceId, "researcher", "task 2", userId) } returns errorRuntime

            val tool =
                makeTool(
                    launcher = launcher,
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
            every { launcher.isCostPaused(any()) } returns false
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
            every { launcher.isCostPaused(any()) } returns false
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
            every { launcher.isCostPaused(any()) } returns false
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
            every { launcher.isCostPaused(any()) } returns false
            val runtime = idleRuntime()
            val events = listOf(agentMessage("resumed result"))
            val tool = makeTool(launcher, events)

            every { launcher.resumeSubCase(subCaseId, parentCaseId, "sub-agent", "follow-up", userId, allowedAgents) } returns runtime

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
            every { launcher.isCostPaused(any()) } returns false
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

        "stops a silent sub-case when its budget runs out and reports it resumable" {
            val launcher = mockk<SubCaseManager>()
            every { launcher.isCostPaused(any()) } returns false
            val status = MutableStateFlow(CaseStatus.RUNNING)
            val runtime = mockk<CaseRuntime>()
            every { runtime.id } returns subCaseId
            every { runtime.statusFlow } returns status
            every { runtime.activityCount } returns 7L
            every { runtime.isRunning() } returns false
            // A Stop ends the sub-case IDLE.
            every { launcher.interruptCase(subCaseId) } answers { status.value = CaseStatus.IDLE }
            val tool = makeTool(launcher, timeoutMs = 200)

            every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns runtime

            val result = tool.execute(singleDelegation(), toolContext)

            result.success shouldBe false
            val delegation = jacksonObjectMapper().readTree(result.output)[0]
            delegation.get("errorType").asText() shouldBe "TIMEOUT"
            delegation.get("error").asText() shouldContain "resume it"
            // Stopped, not killed: the delegating agent can still resume it with its subCaseId.
            verify(exactly = 1) { launcher.interruptCase(subCaseId) }
        }

        "reports a sub-case that could not be stopped at once, without offering to resume it" {
            runTest {
                val launcher = mockk<SubCaseManager>()
                every { launcher.isCostPaused(any()) } returns false
                val runtime = mockk<CaseRuntime>()
                every { runtime.id } returns subCaseId
                every { runtime.statusFlow } returns MutableStateFlow(CaseStatus.RUNNING)
                every { runtime.activityCount } returns 0L
                every { launcher.interruptCase(subCaseId) } throws IllegalStateException("runtime gone")
                every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns runtime
                val tool = makeTool(launcher, timeoutMs = 2_000)

                val result = tool.execute(singleDelegation(), toolContext)

                val error = jacksonObjectMapper().readTree(result.output)[0].get("error").asText()
                error shouldContain "could not be stopped"
                error shouldNotContain "resume"
                // No wait for a stop that did not happen: only the budget elapsed.
                testScheduler.currentTime shouldBe 2_000L
            }
        }

        "reports a stopped sub-case resumable only once its runtime has released its turn" {
            runTest {
                val launcher = mockk<SubCaseManager>()
                every { launcher.isCostPaused(any()) } returns false
                val status = MutableStateFlow(CaseStatus.RUNNING)
                var running = true
                val runtime = mockk<CaseRuntime>()
                every { runtime.id } returns subCaseId
                every { runtime.statusFlow } returns status
                every { runtime.activityCount } returns 0L
                every { runtime.isRunning() } answers { running }
                // The Stop publishes IDLE at once; the runtime releases its turn 300 ms later.
                every { launcher.interruptCase(subCaseId) } answers {
                    status.value = CaseStatus.IDLE
                    launch {
                        delay(300)
                        running = false
                    }
                }
                every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns runtime
                val tool = makeTool(launcher, timeoutMs = 2_000)

                val result = tool.execute(singleDelegation(), toolContext)

                jacksonObjectMapper().readTree(result.output)[0].get("error").asText() shouldContain "resume it"
                (testScheduler.currentTime >= 2_300L) shouldBe true
            }
        }

        "returns the answer of a sub-case that hands back as its budget runs out, without stopping it" {
            runTest {
                val launcher = mockk<SubCaseManager>()
                val status = MutableStateFlow(CaseStatus.RUNNING)
                var pauseChecks = 0
                // The sub-case hands back right after the last sample of its budget, before any stop.
                every { launcher.isCostPaused(any()) } answers {
                    if (++pauseChecks == 2) status.value = CaseStatus.IDLE
                    false
                }
                val runtime = mockk<CaseRuntime>()
                every { runtime.id } returns subCaseId
                every { runtime.statusFlow } returns status
                every { runtime.activityCount } returns 0L
                every { runtime.isRunning() } returns false
                every { launcher.interruptCase(subCaseId) } returns Unit
                every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns runtime
                val tool = makeTool(launcher, events = listOf(agentMessage("just in time")), timeoutMs = 1_000)

                val result = tool.execute(singleDelegation(), toolContext)

                result.success shouldBe true
                jacksonObjectMapper().readTree(result.output)[0].get("result").asText() shouldBe "just in time"
                verify(exactly = 0) { launcher.interruptCase(any()) }
            }
        }

        "keeps waiting while the sub-case shows activity, and relays it to the delegating case" {
            runTest {
                val launcher = mockk<SubCaseManager>()
                every { launcher.isCostPaused(any()) } returns false
                every { launcher.recordActivity(parentCaseId) } returns Unit
                val status = MutableStateFlow(CaseStatus.RUNNING)
                var activity = 0L
                val runtime = mockk<CaseRuntime>()
                every { runtime.id } returns subCaseId
                every { runtime.statusFlow } returns status
                every { runtime.activityCount } answers { activity }
                every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns runtime
                val tool = makeTool(launcher, events = listOf(agentMessage("worked long")), timeoutMs = 300)

                // Active for four times the budget, then answers.
                launch {
                    repeat(12) {
                        delay(100)
                        activity++
                    }
                    status.value = CaseStatus.IDLE
                }

                val result = tool.execute(singleDelegation(), toolContext)

                result.success shouldBe true
                jacksonObjectMapper().readTree(result.output)[0].get("result").asText() shouldBe "worked long"
                verify(atLeast = 1) { launcher.recordActivity(parentCaseId) }
            }
        }

        "two concurrent delegations both suspend then complete independently without cancelling each other" {
            // This test is the direct answer to the coroutine-state coherency question:
            // coroutineScope { async { ... } async { ... } } must wait for BOTH branches
            // to complete. A TimeoutCancellationException caught inside one async must NOT
            // propagate as a CancellationException to the sibling — if it did, the sibling
            // would be cancelled and its result lost.
            //
            // Both runtimes start as RUNNING and transition to IDLE after a real delay,
            // so both coroutines are genuinely suspended at the same time before resolving.
            val subCaseId2 = UUID.randomUUID()
            val launcher = mockk<SubCaseManager>()
            every { launcher.isCostPaused(any()) } returns false
            every { launcher.emitParentEvent(any()) } returns Unit

            val flow1 = MutableStateFlow(CaseStatus.RUNNING)
            val flow2 = MutableStateFlow(CaseStatus.RUNNING)
            val runtime1 = mockk<CaseRuntime> {
                every { id } returns subCaseId
                every { statusFlow } returns flow1
                every { activityCount } returns 0L
            }
            val runtime2 = mockk<CaseRuntime> {
                every { id } returns subCaseId2
                every { statusFlow } returns flow2
                every { activityCount } returns 0L
            }

            every { launcher.startSubCase(parentCaseId, namespaceId, "sub-agent", "task 1", userId) } returns runtime1
            every { launcher.startSubCase(parentCaseId, namespaceId, "researcher", "task 2", userId) } returns runtime2

            val events1 = listOf(agentMessage("result from sub-agent"))
            val events2 = listOf(
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = subCaseId2,
                    actor = Actor(id = UUID.randomUUID().toString(), displayName = "researcher", role = ActorRole.AGENT),
                    content = listOf(MessageContent.Text("result from researcher")),
                ),
            )

            val tool = makeTool(
                launcher = launcher,
                loadCaseEvents = { id -> if (id == subCaseId) events1 else events2 },
                timeoutMs = 2_000,
            )

            // Transition both runtimes to IDLE after a short delay, simulating real
            // concurrent suspension: both coroutines are suspended on statusFlow.first()
            // at the same time before either resolves.
            coroutineScope {
                launch {
                    delay(100)
                    flow1.value = CaseStatus.IDLE
                }
                launch {
                    delay(200)
                    flow2.value = CaseStatus.IDLE
                }

                val args = DelegationTool.Args(
                    delegations = listOf(
                        DelegationTool.Delegation("sub-agent", "task 1"),
                        DelegationTool.Delegation("researcher", "task 2"),
                    ),
                )

                val result = tool.execute(args, toolContext)

                result.success shouldBe true
                val tree = jacksonObjectMapper().readTree(result.output)
                tree.size() shouldBe 2
                val byAgent = (0 until tree.size()).associate { tree[it].get("agentName").asText() to tree[it] }
                byAgent["sub-agent"]!!.get("success").asBoolean() shouldBe true
                byAgent["sub-agent"]!!.get("result").asText() shouldBe "result from sub-agent"
                byAgent["researcher"]!!.get("success").asBoolean() shouldBe true
                byAgent["researcher"]!!.get("result").asText() shouldBe "result from researcher"
            }
        }

        "slow delegation does not cancel a sibling that already succeeded" {
            val subCaseId2 = UUID.randomUUID()
            val launcher = mockk<SubCaseManager>()
            every { launcher.isCostPaused(any()) } returns false
            every { launcher.emitParentEvent(any()) } returns Unit
            val fastRuntime = idleRuntime(subCaseId)
            val slowStatus = MutableStateFlow(CaseStatus.RUNNING) // silent until stopped
            val slowRuntime = mockk<CaseRuntime>()
            every { slowRuntime.id } returns subCaseId2
            every { slowRuntime.statusFlow } returns slowStatus
            every { slowRuntime.activityCount } returns 0L
            every { slowRuntime.isRunning() } returns false
            every { launcher.interruptCase(subCaseId2) } answers { slowStatus.value = CaseStatus.IDLE }

            every { launcher.startSubCase(parentCaseId, namespaceId, "sub-agent", "fast task", userId) } returns fastRuntime
            every { launcher.startSubCase(parentCaseId, namespaceId, "researcher", "slow task", userId) } returns slowRuntime

            val tool = makeTool(
                launcher = launcher,
                loadCaseEvents = { listOf(agentMessage("fast result")) },
                timeoutMs = 300,
            )

            val args = DelegationTool.Args(
                delegations = listOf(
                    DelegationTool.Delegation("sub-agent", "fast task"),
                    DelegationTool.Delegation("researcher", "slow task"),
                ),
            )

            val result = tool.execute(args, toolContext)

            // Overall success because at least one delegation succeeded.
            result.success shouldBe true
            val tree = jacksonObjectMapper().readTree(result.output)
            val byAgent = (0 until tree.size()).associate { tree[it].get("agentName").asText() to tree[it] }
            byAgent["sub-agent"]!!.get("success").asBoolean() shouldBe true
            byAgent["sub-agent"]!!.get("result").asText() shouldBe "fast result"
            byAgent["researcher"]!!.get("success").asBoolean() shouldBe false
            byAgent["researcher"]!!.get("errorType").asText() shouldBe "TIMEOUT"
            // The silent sub-case is stopped.
            verify(exactly = 1) { launcher.interruptCase(subCaseId2) }
        }
    })
