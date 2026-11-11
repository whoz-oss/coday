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
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.caseEvent.CaseEventServiceImpl
import io.whozoss.agentos.caseEvent.InMemoryCaseEventRepository
import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.aiProvider.AiModel
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class CaseServiceModelOverrideSpec :
    StringSpec({
        timeout = 15_000

        "every run reads the case model, so a change applies from the next message" {
            val fixture = ModelOverrideFixture()
            try {
                fixture.sendAndAwaitIdle()
                fixture.service.setModel(fixture.caseId, "BIG")
                fixture.sendAndAwaitIdle()
                fixture.service.setModel(fixture.caseId, null)
                fixture.sendAndAwaitIdle()

                fixture.modelsSeenByRuns shouldBe listOf(null, "BIG", null)
            } finally {
                fixture.service.shutdown()
            }
        }

        "setModel changes only the model and keeps a status changed since the caller's read" {
            val fixture = ModelOverrideFixture()
            try {
                fixture.forceStatus(CaseStatus.KILLED)

                val updated = fixture.service.setModel(fixture.caseId, "BIG")

                updated.modelName shouldBe "BIG"
                updated.status shouldBe CaseStatus.KILLED
                fixture.storedCase().status shouldBe CaseStatus.KILLED
                fixture.storedCase().modelName shouldBe "BIG"
            } finally {
                fixture.service.shutdown()
            }
        }

        "setModel trims the name, and a blank name removes the override" {
            val fixture = ModelOverrideFixture()
            try {
                fixture.service.setModel(fixture.caseId, "  BIG  ").modelName shouldBe "BIG"
                fixture.service.setModel(fixture.caseId, "   ").modelName shouldBe null
            } finally {
                fixture.service.shutdown()
            }
        }

        "setModel refuses a model that no alias or API name answers to, and keeps the current one" {
            val fixture = ModelOverrideFixture()
            try {
                fixture.service.setModel(fixture.caseId, "BIG")

                shouldThrow<BadRequestException> { fixture.service.setModel(fixture.caseId, "nope") }

                fixture.storedCase().modelName shouldBe "BIG"
            } finally {
                fixture.service.shutdown()
            }
        }

        "setModel rejects an unknown case" {
            val fixture = ModelOverrideFixture()
            try {
                shouldThrow<ResourceNotFoundException> { fixture.service.setModel(UUID.randomUUID(), "BIG") }
            } finally {
                fixture.service.shutdown()
            }
        }

        "create refuses an unknown model and accepts a known one" {
            val fixture = ModelOverrideFixture()
            try {
                shouldThrow<BadRequestException> {
                    fixture.service.create(Case(namespaceId = fixture.namespaceId, modelName = "nope"))
                }
                fixture.service.create(Case(namespaceId = fixture.namespaceId, modelName = "BIG")).modelName shouldBe "BIG"
            } finally {
                fixture.service.shutdown()
            }
        }
    })

/** Real [CaseServiceImpl] on in-memory stores; the agent only records the context it is built with. */
private class ModelOverrideFixture {
    val namespaceId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val agentName = "model-agent"
    private val agentId = UUID.randomUUID()
    private val events = CaseEventServiceImpl(InMemoryCaseEventRepository())
    val modelsSeenByRuns = CopyOnWriteArrayList<String?>()
    private val cases = InMemoryCaseRepository()
    private val agents =
        mockk<AgentService> {
            every { resolveAgentName(any(), any(), any()) } returns agentName
            coEvery { findAgentByName(agentName, any(), any()) } answers {
                val context = secondArg<AgentExecutionContext>()
                modelsSeenByRuns.add(context.modelName)
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
    private val user = User(metadata = EntityMetadata(id = userId), externalId = "model-user", email = "model@example.test")
    private val aiModels =
        mockk<AiModelService> {
            // Only the BIG alias exists in the namespace.
            every { findAiModel(namespaceId, any()) } returns null
            every { findAiModel(namespaceId, "BIG") } returns
                AiModel(aiProviderId = UUID.randomUUID(), apiModelName = "claude-big", alias = "BIG")
        }
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
                        Namespace(metadata = EntityMetadata(id = namespaceId), name = "model", defaultAgentName = agentName)
                    every { resolveRunCostThreshold(namespaceId) } returns null
                },
            caseConfig = CaseConfigProperties(),
            permissionService = mockk(relaxed = true),
            promptService = mockk(relaxed = true),
            caseNamingService = mockk(relaxed = true),
            limitsConfig = LimitsConfigProperties(),
            usageRecordService = mockk(relaxed = true),
            usageConfig = UsageConfigProperties(enabled = false),
            aiModelService = aiModels,
            caseListUpdateBroadcaster = mockk(relaxed = true),
        )
    private val case = service.create(Case(namespaceId = namespaceId))
    val caseId: UUID = case.id

    fun storedCase(): Case = checkNotNull(service.findById(caseId))

    /** Write a status straight to the store, as a run ending between two reads would. */
    fun forceStatus(status: CaseStatus) {
        cases.save(storedCase().copy(status = status))
    }

    suspend fun sendAndAwaitIdle() =
        coroutineScope {
            val runtime = service.getCaseRuntime(caseId)
            val idle =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(8_000) {
                        runtime.events.filterIsInstance<CaseStatusEvent>().first { it.status == CaseStatus.IDLE }
                    }
                }
            runtime.subscriptionCount.first { it >= 1 }
            service.addMessage(
                caseId,
                Actor(userId.toString(), "Model User", ActorRole.USER),
                listOf(MessageContent.Text("hello")),
            )
            val idleEvent = idle.await()
            // IDLE is published before run() lets go of the runtime: wait for the release before the next message.
            withTimeout(8_000) { while (runtime.isRunning()) delay(5) }
            idleEvent
        }
}
