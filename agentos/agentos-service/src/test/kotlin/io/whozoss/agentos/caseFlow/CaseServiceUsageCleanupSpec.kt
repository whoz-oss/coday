package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.agent.AgentConfigProperties
import io.whozoss.agentos.agent.AgentExecutionContext
import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseEvent.CaseEventServiceImpl
import io.whozoss.agentos.caseEvent.InMemoryCaseEventRepository
import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.prompt.PromptService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.api.usageRecord.RunCostDto
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.usage.LlmUsage
import io.whozoss.agentos.usage.InMemoryUsageRecordRepository
import io.whozoss.agentos.usage.RunCostService
import io.whozoss.agentos.usage.UsageOutcome
import io.whozoss.agentos.usage.UsageRecordServiceImpl
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class CaseServiceUsageCleanupSpec : StringSpec({
    "failure to store AgentRunningEvent releases the registered cost session without executing the agent" {
        val fixture = UsageCleanupFixture(failRunningEvent = true)
        try {
            fixture.run()

            fixture.statesAtRunningEvent.single().active shouldBe true
            fixture.service.getById(fixture.case.id).status shouldBe CaseStatus.ERROR
            fixture.agentRuns.get() shouldBe 0
            fixture.events.findByParent(fixture.case.id).filterIsInstance<AgentFinishedEvent>() shouldBe emptyList()
            fixture.records.findAll() shouldBe emptyList()
            val state = fixture.costs.state(fixture.case.id)
            state.active shouldBe false
            state.liveTokens shouldBe 0L
            state.cost shouldBe 0.0
            state.unknownCostCount shouldBe 0L
        } finally {
            fixture.service.shutdown()
        }
    }

    "a successful agent run persists its usage and releases the registered cost session" {
        val fixture = UsageCleanupFixture(failRunningEvent = false)
        try {
            fixture.run()

            fixture.statesAtRunningEvent.single().active shouldBe true
            fixture.service.getById(fixture.case.id).status shouldBe CaseStatus.IDLE
            fixture.agentRuns.get() shouldBe 1
            val record = fixture.records.findAll().single()
            record.caseId shouldBe fixture.case.id
            record.totalTokens shouldBe fixture.usage.totalTokens
            record.cost shouldBe fixture.usage.estimatedCostUsd
            record.outcome shouldBe UsageOutcome.COMPLETED
            fixture.events.findByParent(fixture.case.id).filterIsInstance<AgentFinishedEvent>().single().llmUsage shouldBe fixture.usage
            val state = fixture.costs.state(fixture.case.id)
            state.active shouldBe false
            state.liveTokens shouldBe 0L
            state.cost shouldBe fixture.usage.estimatedCostUsd
        } finally {
            fixture.service.shutdown()
        }
    }
})

/** Real case/runtime/cost/usage services, with the event storage fault injected at the service boundary. */
private class UsageCleanupFixture(
    failRunningEvent: Boolean,
) {
    private val namespaceId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val agentName = "usage-cleanup-agent"
    private val user = User(metadata = EntityMetadata(id = userId), externalId = "cleanup-user", email = "cleanup@example.com")
    private val config = UsageConfigProperties(enabled = true)
    private val cases = InMemoryCaseRepository()
    private val backingEvents = CaseEventServiceImpl(InMemoryCaseEventRepository())
    val records = InMemoryUsageRecordRepository()
    private val usageService = UsageRecordServiceImpl(records)
    val usage = LlmUsage(inputTokens = 10L, outputTokens = 5L, totalTokens = 15L, estimatedCostUsd = 0.02)
    val agentRuns = AtomicInteger()
    val statesAtRunningEvent = mutableListOf<RunCostDto>()
    val events: CaseEventService = object : CaseEventService by backingEvents {
        override fun create(entity: CaseEvent): CaseEvent {
            if (entity is AgentRunningEvent) {
                statesAtRunningEvent += costs.state(entity.caseId)
                if (failRunningEvent) throw IllegalStateException("AgentRunningEvent storage unavailable")
            }
            return backingEvents.create(entity)
        }
    }
    private val namespaces = mockk<NamespaceService> {
        every { findById(namespaceId) } returns Namespace(
            metadata = EntityMetadata(id = namespaceId),
            name = "cleanup-namespace",
            defaultAgentName = agentName,
        )
    }
    val costs = RunCostService(cases, events, namespaces, usageService, config)
    private val agentService = mockk<AgentService> {
        every { resolveAgentName(any(), any(), any()) } returns agentName
        coEvery { findAgentByName(agentName, any(), any()) } answers {
            val context = secondArg<AgentExecutionContext>()
            object : Agent {
                override val metadata = EntityMetadata()
                override val name = agentName
                override val llmProvider = "test-provider"
                override val llmModel = "test-model"

                override fun run(events: List<CaseEvent>, shouldContinue: () -> Boolean): Flow<CaseEvent> {
                    agentRuns.incrementAndGet()
                    return flow {
                        context.usageAccumulator.shouldNotBeNull().record(usage)
                        emit(AgentFinishedEvent(namespaceId = namespaceId, caseId = context.caseId.shouldNotBeNull(), agentId = id, agentName = name))
                    }
                }
            }
        }
    }
    val service = CaseServiceImpl(
        agentService = agentService,
        agentConfigService = mockk<AgentConfigService> {
            every { findDeployedByNamespaceIdAndUserIdAndName(any(), any(), any()) } returns
                listOf(AgentConfig(namespaceId = namespaceId, name = agentName))
        },
        agentConfigProperties = AgentConfigProperties(),
        caseRepository = cases,
        caseEventService = events,
        userService = mockk<UserService> {
            every { findById(userId) } returns user
        },
        namespaceService = namespaces,
        caseConfig = CaseConfigProperties(),
        permissionService = mockk<PermissionService>(relaxed = true),
        promptService = mockk<PromptService>(relaxed = true),
        caseNamingService = mockk<CaseNamingService>(relaxed = true),
        limitsConfig = LimitsConfigProperties(),
        usageRecordService = usageService,
        runCostService = costs,
        usageConfig = config,
    )
    val case = service.create(Case(namespaceId = namespaceId, title = "Usage cleanup", runCostThreshold = 10.0))

    suspend fun run() {
        val runtime = service.getCaseRuntime(case.id)
        runtime.addUserMessage(
            Actor(userId.toString(), "Cleanup User", ActorRole.USER),
            listOf(MessageContent.Text("Run once")),
        )
        runtime.run()
    }
}
