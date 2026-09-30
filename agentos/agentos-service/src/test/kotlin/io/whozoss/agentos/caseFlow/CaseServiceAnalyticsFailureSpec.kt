package io.whozoss.agentos.caseFlow

import io.kotest.assertions.throwables.shouldThrowAny
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
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.usage.LlmUsage
import io.whozoss.agentos.usage.InMemoryUsageRecordRepository
import io.whozoss.agentos.usage.RunCostService
import io.whozoss.agentos.usage.UsageCostAggregate
import io.whozoss.agentos.usage.UsageOutcome
import io.whozoss.agentos.usage.UsageRecordService
import io.whozoss.agentos.usage.UsageRecordServiceImpl
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CaseServiceAnalyticsFailureSpec :
    StringSpec({
        timeout = 10_000

        "uncapped usage-enabled execution survives an analytics read failure without reporting complete cost" {
            val fixture = AnalyticsFailureFixture(threshold = null)
            try {
                fixture.runToTerminal() shouldBe CaseStatus.IDLE
                fixture.agentExecutions.get() shouldBe 1
                fixture.liveCostReadFailed.get() shouldBe true
                (fixture.historyReads.get() > 0) shouldBe true
                shouldThrowAny { fixture.costs.state(fixture.case.id) }

                val record = fixture.usageRepository.findAll().single()
                record.caseId shouldBe fixture.case.id
                record.outcome shouldBe UsageOutcome.COMPLETED
                record.totalTokens shouldBe 30L
                record.cost shouldBe 0.042
                val finished = fixture.events.findByParent(fixture.case.id).filterIsInstance<AgentFinishedEvent>().single()
                finished.llmUsage shouldBe fixture.invocationUsage
            } finally {
                fixture.service.shutdown()
            }
        }

        "configured threshold rejects execution when its historical cost cannot be read" {
            val fixture = AnalyticsFailureFixture(threshold = 1.0)
            try {
                fixture.runToTerminal() shouldBe CaseStatus.ERROR
                fixture.agentExecutions.get() shouldBe 0
                (fixture.historyReads.get() > 0) shouldBe true
                fixture.usageRepository.findAll() shouldBe emptyList()
                shouldThrowAny { fixture.costs.state(fixture.case.id) }
            } finally {
                fixture.service.shutdown()
            }
        }
    })

/** Only the historical aggregate read fails; event and usage writes use real in-memory stores. */
private class AnalyticsFailureFixture(threshold: Double?) {
    private val namespaceId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val agentName = "analytics-resilience-agent"
    private val agentId = UUID.randomUUID()
    private val usageConfig = UsageConfigProperties(enabled = true)
    private val cases = InMemoryCaseRepository()
    val events = CaseEventServiceImpl(InMemoryCaseEventRepository())
    val usageRepository = InMemoryUsageRecordRepository()
    val agentExecutions = AtomicInteger()
    val historyReads = AtomicInteger()
    val liveCostReadFailed = AtomicBoolean()
    val invocationUsage = LlmUsage(inputTokens = 20, outputTokens = 10, totalTokens = 30, estimatedCostUsd = 0.042)
    private val records =
        object : UsageRecordService by UsageRecordServiceImpl(usageRepository) {
            override fun sumCostByCaseTreeSince(rootCaseId: UUID, since: Instant): UsageCostAggregate? {
                historyReads.incrementAndGet()
                throw IllegalStateException("historical usage query unavailable")
            }
        }
    private val namespaces =
        mockk<NamespaceService> {
            every { findById(namespaceId) } returns Namespace(
                metadata = EntityMetadata(id = namespaceId),
                name = "analytics-resilience",
                defaultAgentName = agentName,
            )
            // The namespace and platform contribute no monetary threshold.
            every { resolveRunCostThreshold(namespaceId) } returns null
        }
    val costs = RunCostService(cases, events, namespaces, records, usageConfig)
    private val agents =
        mockk<AgentService> {
            every { resolveAgentName(any(), any(), any()) } returns agentName
            coEvery { findAgentByName(agentName, any(), any()) } answers {
                val context = secondArg<AgentExecutionContext>()
                object : Agent {
                    override val metadata = EntityMetadata(id = agentId)
                    override val name = agentName
                    override val llmProvider = "deterministic-provider"
                    override val llmModel = "deterministic-model"

                    override fun run(events: List<CaseEvent>, shouldContinue: () -> Boolean): Flow<CaseEvent> = flow {
                        agentExecutions.incrementAndGet()
                        val accumulator = checkNotNull(context.usageAccumulator)
                        accumulator.beforeCall().join()
                        accumulator.record(invocationUsage)
                        liveCostReadFailed.set(runCatching { costs.state(checkNotNull(context.caseId)) }.isFailure)
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
    private val user = User(metadata = EntityMetadata(id = userId), externalId = "resilience-user", email = "resilience@example.test")
    val service =
        CaseServiceImpl(
            agentService = agents,
            agentConfigService = mockk<AgentConfigService> {
                every { findDeployedByNamespaceIdAndUserIdAndName(any(), any(), any()) } returns
                    listOf(AgentConfig(namespaceId = namespaceId, name = agentName))
            },
            agentConfigProperties = AgentConfigProperties(),
            caseRepository = cases,
            caseEventService = events,
            userService = mockk<UserService> {
                every { findById(userId) } returns user
                every { getById(userId) } returns user
            },
            namespaceService = namespaces,
            caseConfig = CaseConfigProperties(),
            permissionService = mockk(relaxed = true),
            promptService = mockk(relaxed = true),
            caseNamingService = mockk(relaxed = true),
            limitsConfig = LimitsConfigProperties(),
            usageRecordService = records,
            runCostService = costs,
            usageConfig = usageConfig,
        )
    val case = service.create(Case(namespaceId = namespaceId, runCostThreshold = threshold))

    suspend fun runToTerminal(): CaseStatus = coroutineScope {
        val runtime = service.getCaseRuntime(case.id)
        val terminal = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(8_000) {
                runtime.events.filterIsInstance<CaseStatusEvent>().first {
                    it.status == CaseStatus.IDLE || it.status == CaseStatus.ERROR
                }
            }
        }
        runtime.subscriptionCount.first { it >= 1 }
        service.addMessage(
            case.id,
            Actor(userId.toString(), "Resilience User", ActorRole.USER),
            listOf(MessageContent.Text("hello")),
        )
        terminal.await().status
    }
}
