package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.TransientCaseEvent

/**
 * Renders the persisted history of a case as a plain-text transcript, for [CaseSideQuestionService].
 *
 * Why a transcript and not a replay of provider messages: a side question is asked while the case may be
 * running, and the history then ends on a dangling tool request, or holds two consecutive user messages,
 * shapes that providers reject. Text has no such constraint, whatever the state of the run.
 *
 * What it keeps: user and agent messages (with who spoke), the questions the agents asked and their
 * answers, and one short line per tool call (its name only, never its arguments nor its output, which are
 * large and may hold data the side question has no need of). Everything else (status changes, thinking,
 * streaming chunks, warnings, orchestration events) is technical and is left out.
 *
 * The transcript is bounded: when it exceeds the budget the oldest entries are dropped, the most recent
 * part is what a side question is about.
 */
internal object SideQuestionTranscript {
    /** Default budget of the transcript, in characters (about 15k tokens). */
    const val MAX_CHARS = 60_000

    const val EMPTY = "(The conversation has no messages yet.)"
    const val OMITTED_MARKER = "[The earlier part of the conversation is omitted]"

    private const val SEPARATOR = "\n\n"

    fun render(
        events: List<CaseEvent>,
        maxChars: Int = MAX_CHARS,
    ): String {
        val entries = events.mapNotNull(::entryOf)
        if (entries.isEmpty()) return EMPTY

        // Newest first, until the budget is spent: the tail is what matters.
        val kept = ArrayDeque<String>()
        var used = 0
        var omitted = false
        for (entry in entries.asReversed()) {
            val cost = entry.length + if (kept.isEmpty()) 0 else SEPARATOR.length
            if (used + cost <= maxChars) {
                kept.addFirst(entry)
                used += cost
            } else {
                omitted = true
                // A single entry larger than the whole budget is cut, keeping its end.
                if (kept.isEmpty() && maxChars > 1) kept.addFirst("…" + entry.takeLast(maxChars - 1))
                break
            }
        }
        val body = kept.joinToString(SEPARATOR)
        return if (omitted) OMITTED_MARKER + SEPARATOR + body else body
    }

    /** The transcript entry of an event, or null when the event is not part of the conversation. */
    private fun entryOf(event: CaseEvent): String? =
        when {
            event is TransientCaseEvent -> null
            event is MessageEvent -> messageEntry(event)
            event is QuestionEvent -> questionEntry(event)
            event is AnswerEvent -> answerEntry(event)
            event is ToolRequestEvent -> "[Tool call: ${event.toolName}]"
            else -> null
        }

    private fun messageEntry(event: MessageEvent): String? {
        // The Audio part of a voice message has no text: its transcript is the Text part.
        val text =
            event.content
                .filterIsInstance<MessageContent.Text>()
                .joinToString("\n") { it.content }
                .trim()
        return when {
            text.isBlank() -> null
            event.actor.role == ActorRole.USER -> "${speaker("User", event.actor.displayName)}: $text"
            else -> "${speaker("Agent", event.actor.displayName)}: $text"
        }
    }

    private fun questionEntry(event: QuestionEvent): String {
        val options = event.options?.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = " (options: ", postfix = ")") ?: ""
        return "${speaker("Agent", event.agentName)} asked the user: ${event.question.trim()}$options"
    }

    private fun answerEntry(event: AnswerEvent): String = "${speaker("User", event.actor.displayName)} answered: ${event.answer.trim()}"

    private fun speaker(
        role: String,
        name: String?,
    ): String = if (name.isNullOrBlank()) role else "$role $name"
}
