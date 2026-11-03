package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.agent.AdvancedExecutionNotSupportedException
import io.whozoss.agentos.agent.AgentConfigProperties
import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseEvent.CaseEventServiceImpl
import io.whozoss.agentos.caseEvent.InMemoryCaseEventRepository
import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceCaseListUpdateBroadcaster
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * An ADVANCED agent resolved on a model without `supportsAdvancedExecution` must fail the turn AND tell the
 * user why, in the case itself: a log line alone would leave them with an ERROR status and no explanation.
 */
class CaseServiceAdvancedExecutionFailureSpec :
    StringSpec({
        timeout = 10_000

        "an ADVANCED agent on a model without support ends the case in ERROR with a warning naming the cause" {
            val fixture = AdvancedFailureFixture()
            try {
                fixture.runToTerminal() shouldBe CaseStatus.ERROR

                val warning = fixture.events.findByParent(fixture.case.id).filterIsInstance<WarnEvent>().single()
                warning.message shouldBe fixture.failureMessage
                warning.message shouldContain "Agent 'big-advanced'"
                warning.message shouldContain "supportsAdvancedExecution"
            } finally {
                fixture.service.shutdown()
            }
        }
    })

private class AdvancedFailureFixture {
    private val namespaceId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val agentName = "big-advanced"
    val failureMessage =
        "Agent '$agentName' is configured in ADVANCED execution mode but its model 'BIG' " +
            "does not support advanced execution. Ask an administrator to enable supportsAdvancedExecution " +
            "on the model, or to switch the agent to SIMPLE."
    private val cases = InMemoryCaseRepository()
    val events = CaseEventServiceImpl(InMemoryCaseEventRepository())
    private val namespaces =
        mockk<NamespaceService> {
            every { findById(namespaceId) } returns
                Namespace(metadata = EntityMetadata(id = namespaceId), name = "advanced-failure", defaultAgentName = agentName)
            every { resolveRunCostThreshold(namespaceId) } returns null
        }
    private val agents =
        mockk<AgentService> {
            every { resolveAgentName(any(), any(), any()) } returns agentName
            coEvery { findAgentByName(agentName, any(), any()) } throws AdvancedExecutionNotSupportedException(failureMessage)
        }
    private val user = User(metadata = EntityMetadata(id = userId), externalId = "advanced-user", email = "advanced@example.test")
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
            namespaceService = namespaces,
            caseConfig = CaseConfigProperties(),
            permissionService = mockk(relaxed = true),
            promptService = mockk(relaxed = true),
            caseNamingService = mockk(relaxed = true),
            limitsConfig = LimitsConfigProperties(),
            usageRecordService = mockk(relaxed = true),
            caseListUpdateBroadcaster = mockk<NamespaceCaseListUpdateBroadcaster>(relaxed = true),
        )
    val case = service.create(Case(namespaceId = namespaceId))

    suspend fun runToTerminal(): CaseStatus =
        coroutineScope {
            val runtime = service.getCaseRuntime(case.id)
            val terminal =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(8_000) {
                        runtime.events.filterIsInstance<CaseStatusEvent>().first {
                            it.status == CaseStatus.IDLE || it.status == CaseStatus.ERROR
                        }
                    }
                }
            runtime.subscriptionCount.first { it >= 1 }
            service.addMessage(
                case.id,
                Actor(userId.toString(), "Advanced User", ActorRole.USER),
                listOf(MessageContent.Text("hello")),
            )
            terminal.await().status
        }
}
