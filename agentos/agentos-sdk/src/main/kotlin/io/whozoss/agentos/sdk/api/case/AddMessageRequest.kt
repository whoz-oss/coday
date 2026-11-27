package io.whozoss.agentos.sdk.api.case

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

/**
 * Request body for `POST /api/cases/{caseId}/messages`.
 *
 * Adds a user message to a case. [mode] HUMAN stores passive team discussion;
 * AGENT invokes the exact authorized [agentName]. Null preserves legacy agent behavior.
 * The [content] field is the plain-text
 * message body. [answerToEventId] optionally links this message to a prior
 * [io.whozoss.agentos.sdk.caseEvent.QuestionEvent] so the agent can correlate the
 * user's answer with the question it asked.
 *
 * [sessionContext] carries opaque application-level context at send time
 * (e.g. current page type, entity type/id). It is persisted for traceability but
 * never replayed as a conversational message — only the most recent user message
 * that carries a non-null [sessionContext] has it injected into the LLM prompt.
 *
 * [rerunOf] optionally re-runs a previous user message of the case: the conversation restarts from just
 * before that message and [content] takes its place (the non-text parts of the original are kept). It is
 * exclusive with [answerToEventId]. See [io.whozoss.agentos.sdk.caseEvent.MessageEvent.rerunOf].
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AddMessageRequest(
    /** Null preserves legacy agent interaction; HUMAN is passive team discussion. */
    val agentName: String? = null,
    val answerToEventId: UUID? = null,
    val content: String,
    /** Exact deployed agent name, required only in AGENT mode. */
    val mode: CaseMessageMode? = null,
    val rerunOf: UUID? = null,
    val sessionContext: Map<String, Any?>? = null,
)

enum class CaseMessageMode { HUMAN, AGENT }
