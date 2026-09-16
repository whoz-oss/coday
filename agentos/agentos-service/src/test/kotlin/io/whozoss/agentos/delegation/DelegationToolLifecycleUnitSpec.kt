package io.whozoss.agentos.delegation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.caseFlow.CaseRuntime
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.SubCaseFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.SubCaseOutcome
import io.whozoss.agentos.sdk.caseEvent.SubCaseStartedEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.tool.ToolContext
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

class DelegationToolLifecycleUnitSpec : StringSpec({
    val namespaceId = UUID.randomUUID()
    val parentCaseId = UUID.randomUUID()
    val userId = UUID.randomUUID()
    val toolRequestId = "parent-tool-request"
    val objectMapper = jacksonObjectMapper()

    class RecordingManager(private val runtime: CaseRuntime, var startFailure: Throwable? = null) : SubCaseManager {
        val parentEvents = mutableListOf<CaseEvent>()
        var resumed = false
        override fun startSubCase(parentCaseId: UUID, namespaceId: UUID, agentName: String, task: String, userId: UUID): CaseRuntime {
            startFailure?.let { throw it }
            return runtime
        }
        override fun resumeSubCase(subCaseId: UUID, agentName: String, task: String, userId: UUID, allowedAgents: List<String>): CaseRuntime {
            resumed = true
            return runtime
        }
        override fun killCase(caseId: UUID) = Unit
        override fun emitParentEvent(event: CaseEvent) { parentEvents += event }
    }

    fun runtime(id: UUID): CaseRuntime = mockk<CaseRuntime> {
        every { this@mockk.id } returns id
        every { statusFlow } returns MutableStateFlow(CaseStatus.IDLE)
    }
    fun tool(manager: SubCaseManager, events: List<CaseEvent>) = DelegationTool(
        manager, parentCaseId, namespaceId, listOf("researcher"), { events }, timeoutMs = 1_000,
    )
    fun context() = ToolContext(namespaceId, userId, null, emptyList(), toolRequestId = toolRequestId)
    fun args(subCaseId: UUID? = null) = DelegationTool.Args(listOf(DelegationTool.Delegation("researcher", "Investigate", subCaseId)))
    fun agentMessage(caseId: UUID) = MessageEvent(namespaceId = namespaceId, caseId = caseId,
        actor = Actor("agent", "researcher", ActorRole.AGENT), content = listOf(MessageContent.Text("Done")))

    "SUCCESS emits correlated Started and Finished events and exposes their ids in JSON" {
        val subCaseId = UUID.randomUUID()
        val manager = RecordingManager(runtime(subCaseId))
        val result = tool(manager, listOf(agentMessage(subCaseId))).execute(args(), context())

        result.success shouldBe true
        manager.parentEvents shouldHaveSize 2
        val started = manager.parentEvents[0] as SubCaseStartedEvent
        val finished = manager.parentEvents[1] as SubCaseFinishedEvent
        started.delegationId shouldBe finished.delegationId
        started.toolRequestId shouldBe toolRequestId
        finished.toolRequestId shouldBe toolRequestId
        finished.outcome shouldBe SubCaseOutcome.SUCCESS
        val json = objectMapper.readTree(result.output)[0]
        json["delegationId"].asText() shouldBe started.delegationId.toString()
        json["toolRequestId"].asText() shouldBe toolRequestId
        json["subCaseId"].asText() shouldBe subCaseId.toString()
    }

    "WAITING_USER emits a finished observation with WAITING_USER outcome" {
        val subCaseId = UUID.randomUUID()
        val manager = RecordingManager(runtime(subCaseId))
        val question = QuestionEvent(namespaceId = namespaceId, caseId = subCaseId, agentId = UUID.randomUUID(), agentName = "researcher", question = "Which target?")
        val result = tool(manager, listOf(question)).execute(args(), context())

        result.success shouldBe true
        (manager.parentEvents.last() as SubCaseFinishedEvent).outcome shouldBe SubCaseOutcome.WAITING_USER
        objectMapper.readTree(result.output)[0]["pendingQuestion"].asText() shouldBe "Which target?"
    }

    "start failure produces START_FAILED JSON without parent observations" {
        val manager = RecordingManager(runtime(UUID.randomUUID()), IllegalStateException("cannot start"))
        val result = tool(manager, emptyList()).execute(args(), context())

        result.success shouldBe false
        manager.parentEvents shouldHaveSize 0
        objectMapper.readTree(result.output)[0]["errorType"].asText() shouldBe "START_FAILED"
    }

    "resuming a sub-case emits Started with resumed true" {
        val subCaseId = UUID.randomUUID()
        val manager = RecordingManager(runtime(subCaseId))
        tool(manager, listOf(agentMessage(subCaseId))).execute(args(subCaseId), context())

        manager.resumed shouldBe true
        (manager.parentEvents.first() as SubCaseStartedEvent).resumed shouldBe true
    }
})
