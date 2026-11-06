package io.whozoss.agentos.claudeCode

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/**
 * One line of the CLI's `--output-format stream-json --verbose --include-partial-messages` output, reduced
 * to what AgentOS uses. Unknown fields are ignored and unknown lines become [Other], so a newer CLI that
 * adds events does not break a turn.
 */
sealed interface ClaudeCodeStreamEvent {
    /** First line of a turn, emitted once the user message is received. */
    data class Init(
        val sessionId: String,
        val model: String,
        val cliVersion: String?,
        /** `none` when the CLI uses the stored login rather than an API key. */
        val apiKeySource: String?,
        val tools: List<String>,
        val mcpServers: List<McpServerState>,
    ) : ClaudeCodeStreamEvent {
        fun mcpServerStatus(name: String): String? = mcpServers.firstOrNull { it.name == name }?.status
    }

    data class McpServerState(
        val name: String,
        val status: String,
    )

    /** Streamed piece of the answer text. */
    data class TextDelta(
        val text: String,
    ) : ClaudeCodeStreamEvent

    /** Streamed piece of the model's visible reasoning. */
    data class ThinkingDelta(
        val text: String,
    ) : ClaudeCodeStreamEvent

    /**
     * A complete assistant message. [error] is set on the synthetic message the CLI emits instead of
     * calling the model, e.g. `authentication_failed` with the text "Not logged in · Please run /login".
     */
    data class AssistantMessage(
        val content: List<ContentBlock>,
        val error: String?,
    ) : ClaudeCodeStreamEvent {
        val text: String get() = content.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
    }

    sealed interface ContentBlock {
        data class Text(
            val text: String,
        ) : ContentBlock

        data class Thinking(
            val text: String,
        ) : ContentBlock

        data class ToolUse(
            val id: String,
            val name: String,
            val input: JsonNode,
        ) : ContentBlock
    }

    /**
     * The CLI's record of the tool calls of one model step, in parallel when there are several. AgentOS
     * already knows the outputs, having executed the tools.
     */
    data class ToolResults(
        val results: List<ToolResult>,
    ) : ClaudeCodeStreamEvent

    data class ToolResult(
        val toolUseId: String,
        val isError: Boolean,
    )

    /** State of the subscription window the turn draws from. */
    data class RateLimit(
        /** `allowed`, `allowed_warning` or `rejected`. */
        val status: String?,
        val resetsAt: Instant?,
        /** `five_hour`, `seven_day`... */
        val rateLimitType: String?,
        /** Share of the window already used, between 0 and 1, when the CLI reports it. */
        val utilization: Double?,
        /** True when the turn is billed as extra usage rather than drawn from the subscription. */
        val isUsingOverage: Boolean?,
    ) : ClaudeCodeStreamEvent

    /** Last line of a turn. */
    data class Result(
        /** `success`, `error_max_turns`, `error_during_execution`... */
        val subtype: String,
        val isError: Boolean,
        /** The final answer, or the error text when [isError]. */
        val text: String?,
        val sessionId: String?,
        val numTurns: Int,
        /** `completed`, `max_turns`, `api_error`... */
        val terminalReason: String?,
        val usage: Usage,
        /** The CLI's own list-price estimate; the subscription is not billed per token. */
        val totalCostUsd: Double?,
        val errors: List<String>,
    ) : ClaudeCodeStreamEvent

    data class Usage(
        val inputTokens: Long,
        val outputTokens: Long,
        val cacheReadTokens: Long,
        val cacheWriteTokens: Long,
    )

    /** A recognised line AgentOS does not act on, such as `system/status` or a message boundary. */
    data class Other(
        val type: String,
        val subtype: String?,
    ) : ClaudeCodeStreamEvent

    /** A line that is not a JSON object, or exceeds the line limit. Only its size is kept: it may hold case data. */
    data class Malformed(
        val length: Int,
    ) : ClaudeCodeStreamEvent
}
