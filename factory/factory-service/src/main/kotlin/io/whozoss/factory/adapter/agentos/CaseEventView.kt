package io.whozoss.factory.adapter.agentos

/**
 * Local, HTTP-boundary view over a durable AgentOS `CaseEvent` decoded from a
 * JSON `Map<String, Any?>`.
 *
 * The adapter never imports an AgentOS Kotlin type: this is the only event
 * model it knows, mirroring how `HttpAgentOsProxyClient` already reads event
 * lists as plain maps. The `type` field is the JSON discriminant (class simple
 * name on the AgentOS side, e.g. `CaseStatusEvent`, `MessageEvent`).
 */
data class CaseEventView(
    /** `CaseEvent.id` (UUID string) — stable across replay and live; the dedup key. */
    val eventId: String,
    /** JSON discriminant, e.g. `CaseStatusEvent`. */
    val type: String,
    /** Owning case id; used for the strict caseId filter. */
    val caseId: String?,
    /** ISO-8601 timestamp; with [eventId] forms the high-water mark ordering. */
    val timestamp: String?,
    /** The raw decoded JSON event, kept for evidence extraction. */
    val raw: Map<String, Any?>,
) {

    /** Status carried by a `CaseStatusEvent` (`RUNNING`, `IDLE`, `KILLED`, `ERROR`, …), null otherwise. */
    val status: String?
        get() = if (type == CASE_STATUS_EVENT) raw["status"] as? String else null

    /** Question text carried by a `QuestionEvent`, null otherwise. */
    val questionText: String?
        get() = if (type == QUESTION_EVENT) raw["question"] as? String else null

    /** Question input kind (`FREE_TEXT` or `OPEN_CHOICE`), null otherwise. */
    val questionType: String?
        get() = if (type == QUESTION_EVENT) raw["questionType"] as? String else null

    /** Bounded choices carried by an `OPEN_CHOICE` question. */
    val questionOptions: List<String>
        get() = if (type == QUESTION_EVENT) {
            (raw["options"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        } else {
            emptyList()
        }

    /** Optional recipient selected by AgentOS for this question. */
    val questionUserId: String?
        get() = if (type == QUESTION_EVENT) raw["userId"] as? String else null

    /**
     * The `QuestionEvent.id` this `AnswerEvent` resolves, null for any other
     * event type. (The AgentOS correlation is `AnswerEvent.questionId` →
     * `QuestionEvent.id`.)
     */
    val answeredQuestionId: String?
        get() = if (type == ANSWER_EVENT) raw["questionId"] as? String else null

    /** Question selected by an `AgentSelectedEvent`, when AgentOS supplies the correlation. */
    val selectedQuestionId: String?
        get() = if (type == AGENT_SELECTED_EVENT) {
            (raw["questionId"] as? String) ?: (raw["questionEventId"] as? String)
        } else {
            null
        }

    /** True for an agent-authored `MessageEvent` (`actor.role == "AGENT"`). */
    fun isAgentMessage(): Boolean =
        type == MESSAGE_EVENT && (raw["actor"] as? Map<*, *>)?.get("role") == "AGENT"

    /** Flattened text content of a `MessageEvent` (string or content-part list). */
    fun messageText(): String = when (val content = raw["content"]) {
        is String -> content
        is List<*> -> content.joinToString("") { part ->
            ((part as? Map<*, *>)?.get("content") as? String) ?: ""
        }
        else -> ""
    }

    /**
     * Transient events (`ThinkingEvent`, `TextChunkEvent`, `CaseUpdatedEvent`)
     * are never persisted nor replayed by AgentOS; the live SSE stream can
     * still carry them. They are display-only and must never feed verdict or
     * checkpoint logic.
     */
    fun isTransient(): Boolean = type in TRANSIENT_TYPES

    companion object {
        const val CASE_STATUS_EVENT = "CaseStatusEvent"
        const val MESSAGE_EVENT = "MessageEvent"
        const val QUESTION_EVENT = "QuestionEvent"
        const val ANSWER_EVENT = "AnswerEvent"
        const val AGENT_SELECTED_EVENT = "AgentSelectedEvent"
        const val AGENT_FINISHED_EVENT = "AgentFinishedEvent"
        const val TOOL_RESPONSE_EVENT = "ToolResponseEvent"

        /** `TransientCaseEvent` subtypes — display-only, never verdict-relevant. */
        val TRANSIENT_TYPES: Set<String> = setOf("ThinkingEvent", "TextChunkEvent", "CaseUpdatedEvent")

        val QUIESCENT_STATUSES: Set<String> = setOf("IDLE", "KILLED", "ERROR")

        /**
         * Build a view from a decoded JSON event. Returns null when the event
         * carries no usable `id` — without an `eventId` there is no dedup key
         * and the event cannot safely be processed.
         */
        fun fromJson(map: Map<String, Any?>): CaseEventView? {
            val id = (map["id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            return CaseEventView(
                eventId = id,
                type = map["type"] as? String ?: "",
                caseId = map["caseId"] as? String,
                timestamp = map["timestamp"] as? String,
                raw = map,
            )
        }
    }
}
