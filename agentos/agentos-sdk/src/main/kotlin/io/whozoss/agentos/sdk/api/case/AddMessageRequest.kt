package io.whozoss.agentos.sdk.api.case

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

/**
 * Request body for `POST /api/cases/{caseId}/messages`.
 *
 * Adds a user message to a running case. The [content] field is the plain-text
 * message body. [answerToEventId] optionally links this message to a prior
 * [io.whozoss.agentos.sdk.caseEvent.QuestionEvent] so the agent can correlate the
 * user's answer with the question it asked.
 *
 * [sessionContext] carries opaque application-level context at send time
 * (e.g. current page type, entity type/id). It is persisted for traceability but
 * never replayed as a conversational message — only the most recent user message
 * that carries a non-null [sessionContext] has it injected into the LLM prompt.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AddMessageRequest(
    val content: String,
    val answerToEventId: UUID? = null,
    val sessionContext: Map<String, Any?>? = null,
)
