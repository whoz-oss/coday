package io.whozoss.agentos.casePlugin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.tool.ToolContext
import java.time.Instant
import java.util.UUID

class ReadCaseToolUnitSpec : StringSpec({

    val namespaceId: UUID = UUID.randomUUID()
    val targetCaseId: UUID = UUID.randomUUID()

    val context = ToolContext(
        namespaceId = namespaceId,
        userId = null,
        userExternalId = null,
        caseEvents = emptyList(),
    )

    fun sampleEvent(text: String): CaseEvent = MessageEvent(
        namespaceId = namespaceId,
        caseId = targetCaseId,
        timestamp = Instant.parse("2025-01-15T14:23:00Z"),
        actor = Actor(id = "user-1", displayName = "Alice", role = ActorRole.USER),
        content = listOf(MessageContent.Text(text)),
    )

    fun loaderReturning(events: List<CaseEvent>?) = { _: UUID, _: UUID -> events }

    // -------------------------------------------------------------------------
    // Invalid input
    // -------------------------------------------------------------------------

    "returns INVALID_INPUT error when input is null" {
        val tool = ReadCaseTool(
            configName = null,
            includesTechnicalEvents = true,
            caseEventsLoader = loaderReturning(emptyList()),
        )
        val result = tool.execute(null, context)
        result.success shouldBe false
        result.errorType shouldBe "INVALID_INPUT"
    }

    "returns INVALID_INPUT error when caseId is not a valid UUID" {
        val tool = ReadCaseTool(
            configName = null,
            includesTechnicalEvents = true,
            caseEventsLoader = loaderReturning(emptyList()),
        )
        val result = tool.execute(ReadCaseTool.Input(caseId = "not-a-uuid"), context)
        result.success shouldBe false
        result.errorType shouldBe "INVALID_INPUT"
        result.output shouldContain "not-a-uuid"
    }

    // -------------------------------------------------------------------------
    // Not found
    // -------------------------------------------------------------------------

    "returns NOT_FOUND error when caseEventsLoader returns null" {
        val tool = ReadCaseTool(
            configName = null,
            includesTechnicalEvents = true,
            caseEventsLoader = loaderReturning(null),
        )
        val result = tool.execute(ReadCaseTool.Input(caseId = targetCaseId.toString()), context)
        result.success shouldBe false
        result.errorType shouldBe "NOT_FOUND"
    }

    // -------------------------------------------------------------------------
    // Empty case
    // -------------------------------------------------------------------------

    "returns success with 'no events' message when loader returns empty list" {
        val tool = ReadCaseTool(
            configName = null,
            includesTechnicalEvents = true,
            caseEventsLoader = loaderReturning(emptyList()),
        )
        val result = tool.execute(ReadCaseTool.Input(caseId = targetCaseId.toString()), context)
        result.success shouldBe true
        result.output shouldBe "Case has no events"
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    "returns success with transcript when loader returns events" {
        val events = listOf(sampleEvent("hello world"))
        val tool = ReadCaseTool(
            configName = null,
            includesTechnicalEvents = true,
            caseEventsLoader = loaderReturning(events),
        )
        val result = tool.execute(ReadCaseTool.Input(caseId = targetCaseId.toString()), context)
        result.success shouldBe true
        result.output shouldContain "USER: hello world"
    }

    "passes namespaceId from context to caseEventsLoader" {
        var capturedNamespaceId: UUID? = null
        val tool = ReadCaseTool(
            configName = null,
            includesTechnicalEvents = true,
            caseEventsLoader = { _, ns ->
                capturedNamespaceId = ns
                emptyList()
            },
        )
        tool.execute(ReadCaseTool.Input(caseId = targetCaseId.toString()), context)
        capturedNamespaceId shouldBe namespaceId
    }

    "passes parsed caseId to caseEventsLoader" {
        var capturedCaseId: UUID? = null
        val tool = ReadCaseTool(
            configName = null,
            includesTechnicalEvents = true,
            caseEventsLoader = { id, _ ->
                capturedCaseId = id
                emptyList()
            },
        )
        tool.execute(ReadCaseTool.Input(caseId = targetCaseId.toString()), context)
        capturedCaseId shouldBe targetCaseId
    }

    // -------------------------------------------------------------------------
    // Tool name
    // -------------------------------------------------------------------------

    "tool name is 'ReadCase' when configName is null" {
        val tool = ReadCaseTool(
            configName = null,
            includesTechnicalEvents = true,
            caseEventsLoader = loaderReturning(emptyList()),
        )
        tool.name shouldBe "ReadCase"
    }

    "tool name includes configName prefix when provided" {
        val tool = ReadCaseTool(
            configName = "CASE_main",
            includesTechnicalEvents = true,
            caseEventsLoader = loaderReturning(emptyList()),
        )
        tool.name shouldBe "CASE_main__ReadCase"
    }
})
