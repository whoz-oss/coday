package io.whozoss.agentos.claudeCode

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.claudeCode.ClaudeCodeStreamEvent.AssistantMessage
import io.whozoss.agentos.claudeCode.ClaudeCodeStreamEvent.ContentBlock
import io.whozoss.agentos.claudeCode.ClaudeCodeStreamEvent.Init
import io.whozoss.agentos.claudeCode.ClaudeCodeStreamEvent.Malformed
import io.whozoss.agentos.claudeCode.ClaudeCodeStreamEvent.McpServerState
import io.whozoss.agentos.claudeCode.ClaudeCodeStreamEvent.Other
import io.whozoss.agentos.claudeCode.ClaudeCodeStreamEvent.RateLimit
import io.whozoss.agentos.claudeCode.ClaudeCodeStreamEvent.Result
import io.whozoss.agentos.claudeCode.ClaudeCodeStreamEvent.TextDelta
import io.whozoss.agentos.claudeCode.ClaudeCodeStreamEvent.ThinkingDelta
import io.whozoss.agentos.claudeCode.ClaudeCodeStreamEvent.ToolResult
import io.whozoss.agentos.claudeCode.ClaudeCodeStreamEvent.ToolResults
import io.whozoss.agentos.claudeCode.ClaudeCodeStreamEvent.Usage
import java.time.Instant

/** Fixtures are outputs of the real CLI (2.1.317 and 2.1.318), with local paths replaced. */
class ClaudeCodeStreamParserSpec :
    StringSpec({
        fun fixture(name: String): List<ClaudeCodeStreamEvent> =
            javaClass
                .getResource("/claudeCode/$name")!!
                .readText()
                .lines()
                .filter { it.isNotBlank() }
                .map(ClaudeCodeStreamParser::parse)

        "a text turn streams deltas, reports the subscription window and ends with its usage" {
            val events = fixture("text-turn.jsonl")

            events.first() shouldBe
                Init(
                    sessionId = "6c7f4053-1f28-40cf-bc3b-dcb8c2f65454",
                    model = "claude-haiku-5-5",
                    cliVersion = "2.1.317",
                    apiKeySource = "none",
                    tools = emptyList(),
                    mcpServers = emptyList(),
                )
            events.filterIsInstance<TextDelta>() shouldContainExactly listOf(TextDelta("OK"))
            events.filterIsInstance<AssistantMessage>().single().text shouldBe "OK"
            events.filterIsInstance<RateLimit>().single() shouldBe
                RateLimit(
                    status = "allowed",
                    resetsAt = Instant.ofEpochSecond(1793793600),
                    rateLimitType = "seven_day",
                    utilization = 0.23,
                    isUsingOverage = false,
                )
            events.last() shouldBe
                Result(
                    subtype = "success",
                    isError = false,
                    text = "OK",
                    sessionId = "6c7f4053-1f28-40cf-bc3b-dcb8c2f65454",
                    numTurns = 1,
                    terminalReason = "completed",
                    usage = Usage(inputTokens = 2, outputTokens = 4, cacheReadTokens = 0, cacheWriteTokens = 662),
                    totalCostUsd = 0.00013460000000000002,
                    errors = emptyList(),
                )
            events.filterIsInstance<Other>().map { it.type to it.subtype } shouldContainExactly
                listOf(
                    "system" to "status",
                    "stream_event" to "message_start",
                    "stream_event" to "content_block_start",
                    "stream_event" to "content_block_stop",
                    "stream_event" to "message_delta",
                    "stream_event" to "message_stop",
                )
        }

        "a tool turn names the MCP server state, the tool call and its result" {
            val events = fixture("tool-turn.jsonl")

            val init = events.first().shouldBeInstanceOf<Init>()
            init.tools shouldContainExactly listOf("mcp__agentos__DATETIME__now")
            init.mcpServers shouldContainExactly listOf(McpServerState("agentos", "connected"))
            init.mcpServerStatus("agentos") shouldBe "connected"

            val toolUse = events.filterIsInstance<AssistantMessage>().first().content.single().shouldBeInstanceOf<ContentBlock.ToolUse>()
            toolUse.id shouldBe "toolu_01QsNNPF9uwosNB8ZfZ8Mk2A"
            toolUse.name shouldBe "mcp__agentos__DATETIME__now"
            toolUse.input["timezone"].asText() shouldBe "Europe/Paris"

            events.filterIsInstance<ToolResults>().single() shouldBe ToolResults(listOf(ToolResult("toolu_01QsNNPF9uwosNB8ZfZ8Mk2A", false)))
            events.filterIsInstance<AssistantMessage>().last().text shouldBe "Il est 10h00 à Paris (mercredi 4 novembre 2026, heure d'hiver, UTC+1)."
            events.last().shouldBeInstanceOf<Result>().numTurns shouldBe 2
        }

        "a CLI without login answers with a synthetic authentication error, without calling the model" {
            val events = fixture("not-logged-in.jsonl")

            val message = events.filterIsInstance<AssistantMessage>().single()
            message.error shouldBe "authentication_failed"
            message.text shouldBe "Not logged in · Please run /login"
            val result = events.last().shouldBeInstanceOf<Result>()
            result.isError shouldBe true
            result.terminalReason shouldBe "api_error"
            result.text shouldBe "Not logged in · Please run /login"
        }

        "resuming an unknown conversation fails before the model is called" {
            val result = fixture("unknown-session.jsonl").single().shouldBeInstanceOf<Result>()

            result.subtype shouldBe "error_during_execution"
            result.isError shouldBe true
            result.numTurns shouldBe 0
            result.errors shouldContainExactly listOf("No conversation found with session ID: 99999999-2222-4333-8444-555555555555")
        }

        "thinking deltas are kept apart from the answer" {
            ClaudeCodeStreamParser.parse(
                """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me"}}}""",
            ) shouldBe ThinkingDelta("Let me")
        }

        "lines that are not JSON objects keep only their size" {
            ClaudeCodeStreamParser.parse("Warning: something") shouldBe Malformed(18)
            ClaudeCodeStreamParser.parse("[1,2]") shouldBe Malformed(5)
            ClaudeCodeStreamParser.parse("""{"no":"type"}""") shouldBe Malformed(13)
        }

        "an unknown event type is passed through as other" {
            ClaudeCodeStreamParser.parse("""{"type":"control_request","subtype":"can_use_tool"}""") shouldBe Other("control_request", "can_use_tool")
        }
    })
