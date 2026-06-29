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
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.tool.ToolContext
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

class DelegationToolUnitSpec : StringSpec({
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

    fun makeTool(
        launcher: SubCaseLauncher,
        events: List<io.whozoss.agentos.sdk.caseEvent.CaseEvent> = emptyList(),
        timeoutMs: Long = 2_000,
    ) = DelegationTool(
        subCaseLauncher = launcher,
        parentCaseId = parentCaseId,
        namespaceId = namespaceId,
        allowedAgents = allowedAgents,
        loadCaseEvents = { events },
        timeoutMs = timeoutMs,
    )

    fun idleRuntime(): CaseRuntime {
        val runtime = mockk<CaseRuntime>()
        every { runtime.id } returns subCaseId
        every { runtime.statusFlow } returns MutableStateFlow(CaseStatus.IDLE)
        return runtime
    }

    // -------------------------------------------------------------------------
    // Allowlist enforcement
    // -------------------------------------------------------------------------

    "returns failure when agentName is not in allowlist" {
        val tool = makeTool(mockk())

        val result = tool.execute(DelegationTool.Args(agentName = "unknown-agent", task = "do X"), toolContext)

        result.success shouldBe false
        result.output shouldBe "Agent 'unknown-agent' is not in the delegation allowlist. Allowed agents: sub-agent, researcher."
    }

    "returns failure when userId is null in context" {
        val tool = makeTool(mockk())
        val contextWithoutUser = toolContext.copy(userId = null)

        val result = tool.execute(DelegationTool.Args(agentName = "sub-agent", task = "do X"), contextWithoutUser)

        result.success shouldBe false
        result.output shouldBe "Delegation requires a user context (userId is null)."
    }

    "returns failure when input is null" {
        val tool = makeTool(mockk())

        val result = tool.execute(null, toolContext)

        result.success shouldBe false
    }

    // -------------------------------------------------------------------------
    // Nominal path
    // -------------------------------------------------------------------------

    "returns last agent message as JSON when sub-case reaches IDLE" {
        val launcher = mockk<SubCaseLauncher>()
        val runtime = idleRuntime()
        val events = listOf(agentMessage("the result"))
        val tool = makeTool(launcher, events)

        every { launcher.startSubCase(parentCaseId, namespaceId, "sub-agent", "do X", userId) } returns runtime

        val result = tool.execute(DelegationTool.Args(agentName = "sub-agent", task = "do X"), toolContext)

        result.success shouldBe true
        val tree = jacksonObjectMapper().readTree(result.output)
        tree.get("result").asText() shouldBe "the result"
        result.metadata.containsKey("subCaseId") shouldBe true
        result.metadata["subCaseId"] shouldBe subCaseId.toString()
    }

    "concatenates multiple text parts of the last agent message" {
        val launcher = mockk<SubCaseLauncher>()
        val runtime = idleRuntime()
        val multiPartMessage =
            MessageEvent(
                namespaceId = namespaceId,
                caseId = subCaseId,
                actor = Actor(id = UUID.randomUUID().toString(), displayName = "sub-agent", role = ActorRole.AGENT),
                content = listOf(MessageContent.Text("part one"), MessageContent.Text("part two")),
            )
        val tool = makeTool(launcher, listOf(multiPartMessage))

        every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns runtime

        val result = tool.execute(DelegationTool.Args(agentName = "sub-agent", task = "do X"), toolContext)

        result.success shouldBe true
        val tree = jacksonObjectMapper().readTree(result.output)
        tree.get("result").asText() shouldBe "part one\npart two"
    }

    "picks the last agent message when history contains multiple" {
        val launcher = mockk<SubCaseLauncher>()
        val runtime = idleRuntime()
        val events = listOf(agentMessage("first answer"), agentMessage("final answer"))
        val tool = makeTool(launcher, events)

        every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns runtime

        val result = tool.execute(DelegationTool.Args(agentName = "sub-agent", task = "do X"), toolContext)

        result.success shouldBe true
        val tree = jacksonObjectMapper().readTree(result.output)
        tree.get("result").asText() shouldBe "final answer"
    }

    "returns fallback message as JSON when sub-case produces no agent message" {
        val launcher = mockk<SubCaseLauncher>()
        val runtime = idleRuntime()
        val tool = makeTool(launcher, events = emptyList())

        every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns runtime

        val result = tool.execute(DelegationTool.Args(agentName = "sub-agent", task = "do X"), toolContext)

        result.success shouldBe true
        val tree = jacksonObjectMapper().readTree(result.output)
        tree.get("result").asText() shouldBe "Sub-agent completed the task but produced no text output."
    }

    // -------------------------------------------------------------------------
    // Terminal status paths
    // -------------------------------------------------------------------------

    "returns failure when sub-case reaches ERROR status" {
        val launcher = mockk<SubCaseLauncher>()
        val runtime = mockk<CaseRuntime>()
        every { runtime.id } returns subCaseId
        every { runtime.statusFlow } returns MutableStateFlow(CaseStatus.ERROR)
        val tool = makeTool(launcher)

        every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns runtime

        val result = tool.execute(DelegationTool.Args(agentName = "sub-agent", task = "do X"), toolContext)

        result.success shouldBe false
        result.output shouldBe "Sub-case ended with status ERROR without producing a result."
        result.metadata.containsKey("subCaseId") shouldBe true
    }

    "returns failure when sub-case reaches KILLED status" {
        val launcher = mockk<SubCaseLauncher>()
        val runtime = mockk<CaseRuntime>()
        every { runtime.id } returns subCaseId
        every { runtime.statusFlow } returns MutableStateFlow(CaseStatus.KILLED)
        val tool = makeTool(launcher)

        every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns runtime

        val result = tool.execute(DelegationTool.Args(agentName = "sub-agent", task = "do X"), toolContext)

        result.success shouldBe false
    }

    // -------------------------------------------------------------------------
    // Timeout
    // -------------------------------------------------------------------------

    "returns failure and kills sub-case when timeout is reached" {
        val launcher = mockk<SubCaseLauncher>()
        val runtime = mockk<CaseRuntime>()
        every { runtime.id } returns subCaseId
        // statusFlow stays on RUNNING — never reaches IDLE, triggering timeout
        every { runtime.statusFlow } returns MutableStateFlow(CaseStatus.RUNNING)
        every { launcher.killSubCase(subCaseId) } returns Unit
        // Short timeout so the test doesn't wait long
        val tool = makeTool(launcher, timeoutMs = 200)

        every { launcher.startSubCase(any(), any(), any(), any(), any()) } returns runtime

        val result = tool.execute(DelegationTool.Args(agentName = "sub-agent", task = "do X"), toolContext)

        result.success shouldBe false
        result.output shouldBe "Delegation to 'sub-agent' timed out after 0s. The sub-agent did not complete in time."
        result.metadata.containsKey("subCaseId") shouldBe true
        verify(exactly = 1) { launcher.killSubCase(subCaseId) }
    }
})
