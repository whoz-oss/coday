package io.whozoss.agentos.casePlugin

import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.ConfirmationResolvedEvent
import io.whozoss.agentos.sdk.caseEvent.ErrorEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.PendingConfirmationEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.TextChunkEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.caseEvent.ToolSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseUpdatedEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent

/**
 * Converts a list of [CaseEvent]s into a human-readable chronological transcript.
 *
 * Each line follows the format `[<ISO-8601 timestamp>] <TYPE>: <content>`.
 *
 * When [includesTechnicalEvents] is false only conversational events are emitted
 * (messages, questions, answers, warnings and errors). When true all persisted event
 * types are included. Transient events ([ThinkingEvent], [TextChunkEvent]) are never
 * persisted so they never appear in the input list; they are handled here for safety
 * by being silently ignored.
 *
 * [ToolResponseEvent.output] text is truncated to [TOOL_RESPONSE_MAX_CHARS] characters
 * to avoid overwhelming the receiving LLM context window.
 */
object CaseTranscriptFormatter {

    private const val TOOL_RESPONSE_MAX_CHARS = 2000
    private const val TRUNCATION_SUFFIX = "... [truncated]"

    fun format(events: List<CaseEvent>, includesTechnicalEvents: Boolean): String {
        val lines = events.mapNotNull { event ->
            val body = renderBody(event, includesTechnicalEvents) ?: return@mapNotNull null
            "[${event.timestamp}] $body"
        }
        return lines.joinToString("\n")
    }

    private fun renderBody(event: CaseEvent, includesTechnicalEvents: Boolean): String? =
        when (event) {
            // ── Always-included conversational events ──────────────────────────────
            is MessageEvent -> {
                val role = event.actor.role.name
                val text = event.content.joinToString(" ") { renderContent(it) }
                "$role: $text"
            }
            is QuestionEvent -> {
                val options = event.options
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ")
                    ?.let { " [options: $it]" }
                    ?: ""
                "QUESTION (${event.agentName}): ${event.question}$options"
            }
            is AnswerEvent -> "ANSWER: ${event.answer}"
            is WarnEvent -> "WARN: ${event.message}"
            is ErrorEvent -> "ERROR: ${event.message}"

            // ── Technical events — only when includesTechnicalEvents == true ───────
            is AgentSelectedEvent -> if (includesTechnicalEvents) "AGENT_SELECTED: ${event.agentName}" else null
            is AgentRunningEvent -> if (includesTechnicalEvents) "AGENT_RUNNING: ${event.agentName}" else null
            is AgentFinishedEvent -> if (includesTechnicalEvents) "AGENT_FINISHED: ${event.agentName}" else null
            is CaseStatusEvent -> if (includesTechnicalEvents) "STATUS: ${event.status}" else null
            is ToolRequestEvent -> if (includesTechnicalEvents) "TOOL_REQUEST: ${event.toolName} ${event.args ?: ""}" else null
            is ToolResponseEvent -> {
                if (!includesTechnicalEvents) return null
                val status = if (event.success) "success" else "error"
                val duration = event.durationMs?.let { "${it}ms" } ?: "?ms"
                val raw = renderContent(event.output)
                val text = if (raw.length > TOOL_RESPONSE_MAX_CHARS) {
                    raw.take(TOOL_RESPONSE_MAX_CHARS) + TRUNCATION_SUFFIX
                } else {
                    raw
                }
                "TOOL_RESPONSE: [$status, $duration] $text"
            }
            is IntentionGeneratedEvent -> {
                if (includesTechnicalEvents) "INTENTION (${event.agentId}): ${event.intention} → ${event.toolName}" else null
            }
            is ToolSelectedEvent -> {
                if (includesTechnicalEvents) "TOOL_SELECTED (${event.agentId}): ${event.toolName}" else null
            }
            is PendingConfirmationEvent -> {
                if (includesTechnicalEvents) "PENDING_CONFIRMATION: ${event.toolName} ${event.inputJson}" else null
            }
            is ConfirmationResolvedEvent -> {
                if (includesTechnicalEvents) "CONFIRMATION_RESOLVED: confirmed=${event.confirmed} ${event.resultText}" else null
            }

            // ── Transient events — never persisted, silently ignored ───────────────
            is ThinkingEvent -> null
            is TextChunkEvent -> null
            is CaseUpdatedEvent -> null
        }

    private fun renderContent(content: MessageContent): String =
        when (content) {
            is MessageContent.Text -> content.content
            is MessageContent.Image -> "[image]"
        }
}
