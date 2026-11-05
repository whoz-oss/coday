package io.whozoss.agentos.claudeCode

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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

/** Turns one line of the CLI's `stream-json` output into a [ClaudeCodeStreamEvent]. */
object ClaudeCodeStreamParser {
    private val mapper = jacksonObjectMapper()

    fun parse(line: String): ClaudeCodeStreamEvent {
        val node = runCatching { mapper.readTree(line) }.getOrNull()
        if (node == null || !node.isObject) return Malformed(line.length)
        val type = node.text("type") ?: return Malformed(line.length)
        return when (type) {
            "system" -> if (node.text("subtype") == "init") init(node) else Other(type, node.text("subtype"))
            "stream_event" -> streamEvent(node.path("event"))
            "assistant" -> assistant(node)
            "user" -> toolResults(node) ?: Other(type, null)
            "rate_limit_event" -> rateLimit(node.path("rate_limit_info"))
            "result" -> result(node)
            else -> Other(type, node.text("subtype"))
        }
    }

    private fun init(node: JsonNode): Init =
        Init(
            sessionId = node.text("session_id").orEmpty(),
            model = node.text("model").orEmpty(),
            cliVersion = node.text("claude_code_version"),
            apiKeySource = node.text("apiKeySource"),
            tools = node.path("tools").mapNotNull { it.textValue() },
            mcpServers =
                node.path("mcp_servers").mapNotNull { server ->
                    val name = server.text("name") ?: return@mapNotNull null
                    McpServerState(name, server.text("status").orEmpty())
                },
        )

    private fun streamEvent(event: JsonNode): ClaudeCodeStreamEvent {
        val eventType = event.text("type") ?: "unknown"
        if (eventType == "content_block_delta") {
            val delta = event.path("delta")
            when (delta.text("type")) {
                "text_delta" -> return TextDelta(delta.text("text").orEmpty())
                "thinking_delta" -> return ThinkingDelta(delta.text("thinking").orEmpty())
            }
        }
        return Other("stream_event", eventType)
    }

    private fun assistant(node: JsonNode): AssistantMessage =
        AssistantMessage(
            content =
                node.path("message").path("content").mapNotNull { block ->
                    when (block.text("type")) {
                        "text" -> ContentBlock.Text(block.text("text").orEmpty())
                        "thinking" -> ContentBlock.Thinking(block.text("thinking").orEmpty())
                        "tool_use" -> ContentBlock.ToolUse(block.text("id").orEmpty(), block.text("name").orEmpty(), block.path("input"))
                        else -> null
                    }
                },
            error = node.text("error"),
        )

    private fun toolResults(node: JsonNode): ToolResults? =
        node
            .path("message")
            .path("content")
            .filter { it.text("type") == "tool_result" }
            .map { ToolResult(it.text("tool_use_id").orEmpty(), it.path("is_error").asBoolean(false)) }
            .takeIf { it.isNotEmpty() }
            ?.let(::ToolResults)

    private fun rateLimit(info: JsonNode): RateLimit {
        val windowType = info.text("rateLimitType")
        return RateLimit(
            status = info.text("status"),
            resetsAt = info.path("resetsAt").takeIf { it.isNumber }?.let { Instant.ofEpochSecond(it.asLong()) },
            rateLimitType = windowType,
            utilization =
                windowType
                    ?.let { info.path("unifiedWindows").path(it).path("utilization") }
                    ?.takeIf { it.isNumber }
                    ?.asDouble(),
            isUsingOverage = info.path("isUsingOverage").takeIf { it.isBoolean }?.asBoolean(),
        )
    }

    private fun result(node: JsonNode): Result {
        val usage = node.path("usage")
        return Result(
            subtype = node.text("subtype").orEmpty(),
            isError = node.path("is_error").asBoolean(false),
            text = node.text("result"),
            sessionId = node.text("session_id"),
            numTurns = node.path("num_turns").asInt(0),
            terminalReason = node.text("terminal_reason"),
            usage =
                Usage(
                    inputTokens = usage.path("input_tokens").asLong(0),
                    outputTokens = usage.path("output_tokens").asLong(0),
                    cacheReadTokens = usage.path("cache_read_input_tokens").asLong(0),
                    cacheWriteTokens = usage.path("cache_creation_input_tokens").asLong(0),
                ),
            totalCostUsd = node.path("total_cost_usd").takeIf { it.isNumber }?.asDouble(),
            errors = node.path("errors").mapNotNull { it.textValue() },
        )
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isTextual }?.textValue()
}
