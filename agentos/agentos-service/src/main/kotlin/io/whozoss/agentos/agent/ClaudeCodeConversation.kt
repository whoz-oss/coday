package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import java.util.UUID

/**
 * What a Claude Code agent sends to the CLI for a run, read from the case events.
 *
 * The CLI keeps the conversation of the agent (its messages, tool calls and results) in its own session.
 * When the agent's last run left that session ([AgentFinishedEvent.runtimeSessionId]), the run resumes it
 * and sends only what happened since: user messages, answers and the other agents' messages. Otherwise
 * (first run, a run that failed or was stopped by AgentOS, a session the CLI no longer has), the run
 * starts a new session whose first message replays the case so far, capped to the most recent part.
 *
 * Messages are tagged as for the other runtimes ([toSpringAiMessage]): `<user name="…">`,
 * `<agent name="…">`, and the session context of the last user message is placed just before it.
 */
internal class ClaudeCodeConversation(
    private val events: List<CaseEvent>,
    private val agentId: UUID,
) {
    private val lastRunIndex = events.indexOfLast { it is AgentFinishedEvent && it.agentId == agentId }

    /** The session to resume, when the agent's last run ended normally on one. */
    val resumableSessionId: UUID? =
        (events.getOrNull(lastRunIndex) as? AgentFinishedEvent)
            ?.runtimeSessionId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private val sinceLastRun: List<IndexedValue<CaseEvent>> = events.withIndex().drop(lastRunIndex + 1)

    private val lastUserMessageIndex = events.indexOfLast { it is MessageEvent && it.actor.role == ActorRole.USER }

    /** The message of a run that resumes [resumableSessionId]. */
    fun resumeMessage(): String = render(sinceLastRun, ownMessages = false).ifBlank { CONTINUE }

    /**
     * The first message of a new session: the case before the agent's last run as a recovered history,
     * then what happened since. A first run has no recovered history.
     */
    fun newSessionMessage(): String {
        val history = render(events.withIndex().take(lastRunIndex + 1), ownMessages = true)
        val current = render(sinceLastRun, ownMessages = false).ifBlank { CONTINUE }
        if (history.isBlank()) return current
        val kept = if (history.length > MAX_HISTORY_CHARS) "[…]\n" + history.takeLast(MAX_HISTORY_CHARS) else history
        return "$HISTORY_HEADER\n$kept\n$HISTORY_FOOTER\n\n$current"
    }

    /**
     * Renders [part] in order. [ownMessages] includes the agent's own messages, questions and tool calls,
     * which a resumed session already holds.
     */
    private fun render(
        part: List<IndexedValue<CaseEvent>>,
        ownMessages: Boolean,
    ): String =
        part
            .mapNotNull { (index, event) ->
                when (event) {
                    is MessageEvent -> {
                        val text = event.text()
                        when {
                            event.actor.role == ActorRole.USER -> {
                                val context = event.sessionContextPromptText()?.takeIf { index == lastUserMessageIndex }
                                listOfNotNull(context, """<user name="${event.actor.displayName}">$text</user>""").joinToString("\n")
                            }

                            event.actor.id == agentId.toString() -> if (ownMessages) "<you>$text</you>" else null
                            else -> """<agent name="${event.actor.displayName}">$text</agent>"""
                        }
                    }

                    is QuestionEvent -> if (ownMessages && event.agentId == agentId) "<you>${event.toPromptText()}</you>" else null
                    is AnswerEvent -> """<user name="${event.actor.displayName}">${event.answer}</user>"""
                    is ToolRequestEvent -> if (ownMessages) """<tool-call name="${event.toolName}">${event.args.orEmpty().cap()}</tool-call>""" else null
                    is ToolResponseEvent -> if (ownMessages) """<tool-result name="${event.toolName}">${event.outputText().cap()}</tool-result>""" else null
                    else -> null
                }
            }.joinToString("\n\n")

    private fun MessageEvent.text(): String =
        content.joinToString("\n") {
            when (it) {
                is MessageContent.Text -> it.content
                // Images reach Claude Code only through tools for now.
                is MessageContent.Image -> "[image not shown]"
            }
        }

    private fun ToolResponseEvent.outputText(): String =
        when (val content = output) {
            is MessageContent.Text -> content.content
            is MessageContent.Image -> "[image]"
        }

    private fun String.cap(): String = if (length > MAX_TOOL_CHARS) take(MAX_TOOL_CHARS) + "[…]" else this

    companion object {
        /** Sent when nothing new happened since the agent's last run, e.g. after a redirect back to it. */
        const val CONTINUE = "Continue."

        /** Recovered history kept in a new session's first message, most recent part first. */
        const val MAX_HISTORY_CHARS = 200_000

        /** A tool call or result in the recovered history is cut beyond this. */
        const val MAX_TOOL_CHARS = 2_000

        private const val HISTORY_HEADER =
            "[Conversation so far in this case, recovered by AgentOS. Your earlier messages are tagged <you>. " +
                "Tool results may be outdated: call the tool again when you need current data.]"
        private const val HISTORY_FOOTER = "[End of the recovered conversation. New messages:]"
    }
}
