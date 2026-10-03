package io.whozoss.factory.agentattempt.web

import com.fasterxml.jackson.databind.JsonNode

/**
 * Request body of `POST /api/factory/agent-step-questions` (Phase 4
 * ask-step-question).
 *
 *   * [attemptId] — the declared attempt identity (must match the issued
 *     capability exactly);
 *   * [question] — the structured step question (`prompt`, `type`, optional
 *     `options`, `recipientRole`, `contextHash`, `expiresAt`).
 *
 * All fields are nullable so a malformed body surfaces as a 400
 * `INVALID_RESULT_REQUEST` rather than a deserialization failure. Identity
 * fields (`caseId`, `agentName`, `namespaceId`) are NEVER model-authored
 * inputs: the case/agent are injected from the trusted `X-AgentOS-*` headers
 * and the namespace from the verified `TrustContext`.
 */
data class AgentStepQuestionRequest(
    val attemptId: String? = null,
    val question: JsonNode? = null,
)

/** Canonical HTTP success envelope: `{ "data": ... }`. */
data class AgentStepQuestionEnvelope<T>(
    val data: T,
)

/** Response payload of a recorded step question. */
data class AgentStepQuestionData(
    val attemptId: String,
    val interactionId: String,
    val status: String,
    val idempotent: Boolean,
)
