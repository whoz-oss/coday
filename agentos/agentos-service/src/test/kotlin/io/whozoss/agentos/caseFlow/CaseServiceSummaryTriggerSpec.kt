package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.mockk.coEvery
import io.mockk.coVerify
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

/**
 * When [CaseServiceImpl] asks for a case summary (#1566): the first one after an agent turn, then one
 * every [CaseServiceImpl.SUMMARY_REFRESH_USER_MESSAGES] user messages, never for a sub-case, and not at
 * all when `agentos.case.summary-enabled` is off.
 */
class CaseServiceSummaryTriggerSpec :
    StringSpec({
        timeout = 30_000

        "summarizes after the first agent turn" {
            val fixture = SummaryTriggerFixture()
            try {
                fixture.sendAndAwaitIdle()

                coVerify(exactly = 1, timeout = 5_000) { fixture.summaryService.summarizeCase(any(), any()) }
            } finally {
                fixture.service.shutdown()
            }
        }

        "refreshes an existing summary only once 3 user messages followed it" {
            val fixture = SummaryTriggerFixture()
            try {
                fixture.sendAndAwaitIdle()
                coVerify(exactly = 1, timeout = 5_000) { fixture.summaryService.summarizeCase(any(), any()) }
                // What the real summary service stores: a summary covering the 1 user message so far.
                fixture.storeSummary(userMessages = 1)

                fixture.sendAndAwaitIdle()
                fixture.sendAndAwaitIdle()
                delay(300)
                coVerify(exactly = 1) { fixture.summaryService.summarizeCase(any(), any()) }

                fixture.sendAndAwaitIdle()
                coVerify(exactly = 2, timeout = 5_000) { fixture.summaryService.summarizeCase(any(), any()) }
            } finally {
                fixture.service.shutdown()
            }
        }

        "never summarizes a sub-case" {
            val fixture = SummaryTriggerFixture(subCase = true)
            try {
                fixture.sendAndAwaitIdle()
                delay(300)

                coVerify(exactly = 0) { fixture.summaryService.summarizeCase(any(), any()) }
            } finally {
                fixture.service.shutdown()
            }
        }

        "does not summarize when the feature is disabled" {
            val fixture = SummaryTriggerFixture(summaryEnabled = false)
            try {
                fixture.sendAndAwaitIdle()
                delay(300)

                coVerify(exactly = 0) { fixture.summaryService.summarizeCase(any(), any()) }
            } finally {
                fixture.service.shutdown()
            }
        }
    })

/** Real [CaseServiceImpl] on in-memory stores, with a deterministic agent and a mocked summary service. */
private class SummaryTriggerFixture(
    subCase: Boolean = false,
    summaryEnabled: Boolean = true,
) {
    private val namespaceId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val agentName = "summary-agent"
    private val agentId = UUID.randomUUID()
    private val events = CaseEventServiceImpl(InMemoryCaseEventRepository())
    private val cases = InMemoryCaseRepository()
    val summaryService: CaseSummaryService = mockk(relaxed = true)
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
    private val user = User(metadata = EntityMetadata(id = userId), externalId = "summary-user", email = "summary@example.test")
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
                        Namespace(metadata = EntityMetadata(id = namespaceId), name = "summary", defaultAgentName = agentName)
                    every { resolveRunCostThreshold(namespaceId) } returns null
                },
            caseConfig = CaseConfigProperties(summaryEnabled = summaryEnabled),
            permissionService = mockk(relaxed = true),
            promptService = mockk(relaxed = true),
            caseNamingService = mockk(relaxed = true),
            limitsConfig = LimitsConfigProperties(),
            usageRecordService = mockk(relaxed = true),
            usageConfig = UsageConfigProperties(enabled = false),
            caseListUpdateBroadcaster = mockk(relaxed = true),
            caseSummaryService = summaryService,
        )

    // A sub-case is written straight to the store: create() would need a real parent case.
    private val case =
        if (subCase) {
            cases.save(Case(namespaceId = namespaceId, parentCaseId = UUID.randomUUID(), status = CaseStatus.IDLE))
        } else {
            service.create(Case(namespaceId = namespaceId))
        }

    /** Write the summary the real [CaseSummaryService] would have stored. */
    fun storeSummary(userMessages: Int) {
        val stored = checkNotNull(service.findById(case.id))
        cases.save(stored.copy(summary = "A summary", summaryMessageCount = userMessages))
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
                Actor(userId.toString(), "Summary User", ActorRole.USER),
                listOf(MessageContent.Text("hello")),
            )
            val idleEvent = idle.await()
            // IDLE is published before run() lets go of the runtime. A message sent in that window finds the run
            // still in flight and is skipped (see CaseServiceImpl.resumeSubCase), so the next wait would only end
            // on its timeout. Wait for the release before the next message.
            withTimeout(8_000) { while (runtime.isRunning()) delay(5) }
            idleEvent
        }
}
