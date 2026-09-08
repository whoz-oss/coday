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
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.SubCaseFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.SubCaseStartedEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.tool.ToolContext
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/** Contract tests for durable parent-case delegation lifecycle events. */
class DelegationToolLifecycleUnitSpec : StringSpec({
    val parentCaseId = UUID.randomUUID()
    val namespaceId = UUID.randomUUID()
    val userId = UUID.randomUUID()
    val subCaseId = UUID.randomUUID()
    val context = ToolContext(namespaceId = namespaceId, userId = userId, userExternalId = null, caseEvents = emptyList(), toolRequestId = "parent-tool-request")

    fun tool(manager: SubCaseManager, events: List<CaseEvent> = emptyList(), timeoutMs: Long = 1_000L) = DelegationTool(
        subCaseManager = manager, parentCaseId = parentCaseId, namespaceId = namespaceId,
        allowedAgents = listOf("worker"), loadCaseEvents = { events }, timeoutMs = timeoutMs,
    )

    fun args(resumeId: UUID? = null) = DelegationTool.Args(listOf(DelegationTool.Delegation("worker", "do work", resumeId)))
    fun runtime(status: CaseStatus) = mockk<CaseRuntime> {
        every { id } returns subCaseId
        every { statusFlow } returns MutableStateFlow(status)
    }

    "emits exactly Started then Finished SUCCESS with the same delegation id" {
        val manager = mockk<SubCaseManager>(relaxed = true)
        val emitted = mutableListOf<CaseEvent>()
        every { manager.startSubCase(parentCaseId, namespaceId, "worker", "do work", userId) } returns runtime(CaseStatus.IDLE)
        every { manager.emitParentEvent(any()) } answers { emitted += firstArg<CaseEvent>() }
        val events = listOf(MessageEvent(namespaceId = namespaceId, caseId = subCaseId, actor = Actor("agent", "worker", ActorRole.AGENT), content = listOf(MessageContent.Text("done"))))

        val result = tool(manager, events).execute(args(), context)
        result.success shouldBe true
        emitted.size shouldBe 2
        val started = emitted[0] as SubCaseStartedEvent
        val finished = emitted[1] as SubCaseFinishedEvent
        started.subCaseId shouldBe subCaseId
        finished.subCaseId shouldBe subCaseId
        finished.delegationId shouldBe started.delegationId
        started.toolRequestId shouldBe "parent-tool-request"
        finished.toolRequestId shouldBe "parent-tool-request"
        finished.outcome.name shouldBe "SUCCESS"
        jacksonObjectMapper().readTree(result.output)[0].get("delegationId").asText() shouldBe started.delegationId.toString()
        jacksonObjectMapper().readTree(result.output)[0].get("toolRequestId").asText() shouldBe "parent-tool-request"
    }

    "emits WAITING_USER on an idle sub-case with a pending question" {
        val manager = mockk<SubCaseManager>(relaxed = true)
        val emitted = mutableListOf<CaseEvent>()
        every { manager.startSubCase(any(), any(), any(), any(), any()) } returns runtime(CaseStatus.IDLE)
        every { manager.emitParentEvent(any()) } answers { emitted += firstArg<CaseEvent>() }
        val question = io.whozoss.agentos.sdk.caseEvent.QuestionEvent(namespaceId = namespaceId, caseId = subCaseId, agentId = UUID.randomUUID(), agentName = "worker", question = "Need input")

        tool(manager, listOf(question)).execute(args(), context)
        (emitted.last() as SubCaseFinishedEvent).outcome.name shouldBe "WAITING_USER"
    }

    "emits no lifecycle event when starting the sub-case fails but returns its delegation id" {
        val manager = mockk<SubCaseManager>(relaxed = true)
        every { manager.startSubCase(any(), any(), any(), any(), any()) } throws IllegalStateException("boom")
        val result = tool(manager).execute(args(), context)
        val entry = jacksonObjectMapper().readTree(result.output)[0]
        result.success shouldBe false
        entry.get("delegationId").asText().isNotBlank() shouldBe true
        entry.get("errorType").asText() shouldBe "START_FAILED"
        verify(exactly = 0) { manager.emitParentEvent(any()) }
    }

    "resuming emits Started with resumed=true and a new delegation id" {
        val manager = mockk<SubCaseManager>(relaxed = true)
        val emitted = mutableListOf<CaseEvent>()
        every { manager.resumeSubCase(subCaseId, "worker", "do work", userId, listOf("worker")) } returns runtime(CaseStatus.IDLE)
        every { manager.emitParentEvent(any()) } answers { emitted += firstArg<CaseEvent>() }

        tool(manager).execute(args(subCaseId), context)
        (emitted.first() as SubCaseStartedEvent).resumed shouldBe true
    }
})
