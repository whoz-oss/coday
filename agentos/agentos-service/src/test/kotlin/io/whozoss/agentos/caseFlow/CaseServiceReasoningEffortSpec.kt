package io.whozoss.agentos.caseFlow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.agent.AgentConfigProperties
import io.whozoss.agentos.agent.AgentExecutionContext
import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseEvent.CaseEventServiceImpl
import io.whozoss.agentos.caseEvent.InMemoryCaseEventRepository
import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class CaseServiceReasoningEffortSpec :
    StringSpec({
        timeout = 15_000

        "every run reads the case reasoning effort, so a change applies from the next message" {
            val fixture = ReasoningEffortFixture()
            try {
                fixture.sendAndAwaitIdle()
                fixture.setCaseEffort(ReasoningEffort.HIGH)
                fixture.sendAndAwaitIdle()
                fixture.setCaseEffort(null)
                fixture.sendAndAwaitIdle()

                fixture.effortsSeenByRuns shouldBe listOf(null, ReasoningEffort.HIGH, null)
            } finally {
                fixture.service.shutdown()
            }
        }

        "setReasoningEffort changes only the effort and keeps a status changed since the caller's read" {
            val fixture = ReasoningEffortFixture()
            try {
                val callerCopy = fixture.storedCase()
                fixture.forceStatus(CaseStatus.KILLED)

                val updated = fixture.service.setReasoningEffort(callerCopy.id, ReasoningEffort.HIGH)

                updated.reasoningEffort shouldBe ReasoningEffort.HIGH
                updated.status shouldBe CaseStatus.KILLED
                fixture.storedCase().status shouldBe CaseStatus.KILLED
                fixture.storedCase().reasoningEffort shouldBe ReasoningEffort.HIGH
            } finally {
                fixture.service.shutdown()
            }
        }

        "setReasoningEffort rejects an unknown case" {
            val fixture = ReasoningEffortFixture()
            try {
                shouldThrow<ResourceNotFoundException> {
                    fixture.service.setReasoningEffort(UUID.randomUUID(), ReasoningEffort.LOW)
                }
            } finally {
                fixture.service.shutdown()
            }
        }
    })

/** Real [CaseServiceImpl] on in-memory stores; the agent only records the context it is built with. */
private class ReasoningEffortFixture {
    private val namespaceId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val agentName = "effort-agent"
    private val agentId = UUID.randomUUID()
    private val events = CaseEventServiceImpl(InMemoryCaseEventRepository())
    val effortsSeenByRuns = CopyOnWriteArrayList<ReasoningEffort?>()
    private val cases = InMemoryCaseRepository()
    private val agents =
        mockk<AgentService> {
            every { resolveAgentName(any(), any(), any()) } returns agentName
            coEvery { findAgentByName(agentName, any(), any()) } answers {
                val context = secondArg<AgentExecutionContext>()
                effortsSeenByRuns.add(context.reasoningEffort)
                object : Agent {
                    override val metadata = EntityMetadata(id = agentId)
                    override val name = agentName
                    override val llmProvider = "deterministic-provider"
                    override val llmModel = "deterministic-model"

                    override fun run(
                        events: List<CaseEvent>,
                        shouldContinue: () -> Boolean,
                    ): Flow<CaseEvent> =
                        flow {
                            emit(
                                AgentFinishedEvent(
                                    namespaceId = namespaceId,
                                    caseId = checkNotNull(context.caseId),
                                    agentId = agentId,
                                    agentName = agentName,
                                ),
                            )
                        }
                }
            }
        }
    private val user = User(metadata = EntityMetadata(id = userId), externalId = "effort-user", email = "effort@example.test")
    val service =
        CaseServiceImpl(
            agentService = agents,
            agentConfigService =
                mockk<AgentConfigService> {
                    every { findDeployedByNamespaceIdAndUserIdAndName(any(), any(), any()) } returns
                        listOf(AgentConfig(namespaceId = namespaceId, name = agentName))
                },
            agentConfigProperties = AgentConfigProperties(),
            caseRepository = cases,
            caseEventService = events,
            userService =
                mockk<UserService> {
                    every { findById(userId) } returns user
                    every { getById(userId) } returns user
                },
            namespaceService =
                mockk<NamespaceService> {
                    every { findById(namespaceId) } returns
                        Namespace(metadata = EntityMetadata(id = namespaceId), name = "effort", defaultAgentName = agentName)
                    every { resolveRunCostThreshold(namespaceId) } returns null
                },
            caseConfig = CaseConfigProperties(),
            permissionService = mockk(relaxed = true),
            promptService = mockk(relaxed = true),
            caseNamingService = mockk(relaxed = true),
            limitsConfig = LimitsConfigProperties(),
            usageRecordService = mockk(relaxed = true),
            usageConfig = UsageConfigProperties(enabled = false),
        )
    private val case = service.create(Case(namespaceId = namespaceId))

    fun setCaseEffort(effort: ReasoningEffort?) {
        service.setReasoningEffort(case.id, effort)
    }

    fun storedCase(): Case = checkNotNull(service.findById(case.id))

    /** Write a status straight to the store, as a run ending between two reads would. */
    fun forceStatus(status: CaseStatus) {
        cases.save(storedCase().copy(status = status))
    }

    suspend fun sendAndAwaitIdle() =
        coroutineScope {
            val runtime = service.getCaseRuntime(case.id)
            val idle =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(8_000) {
                        runtime.events.filterIsInstance<CaseStatusEvent>().first { it.status == CaseStatus.IDLE }
                    }
                }
            runtime.subscriptionCount.first { it >= 1 }
            service.addMessage(
                case.id,
                Actor(userId.toString(), "Effort User", ActorRole.USER),
                listOf(MessageContent.Text("hello")),
            )
            idle.await()
        }
}
