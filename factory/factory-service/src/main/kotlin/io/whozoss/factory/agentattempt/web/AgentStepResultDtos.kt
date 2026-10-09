package io.whozoss.factory.agentattempt.web

import com.fasterxml.jackson.databind.JsonNode

/**
 * Request body of `POST /api/factory/agent-step-results`.
 *
 * Two wire shapes are accepted so both the Node dashboard contract
 * (`{ attemptId, result }` + `X-AgentOS-*` headers) and the newer explicit shape
 * (`{ business, observed: { attemptId, caseId, agentName } }`) are served:
 *
 *   * [result] / [business] — the structured business result (required);
 *   * [attemptId] / [observed] — the declared attempt identity.
 *
 * All fields are nullable so a malformed body surfaces as a 400
 * `INVALID_RESULT_REQUEST` rather than a deserialization failure.
 */
data class AgentStepResultRequest(
    val attemptId: String? = null,
    val result: JsonNode? = null,
    val business: JsonNode? = null,
    val observed: ObservedIdentityRequest? = null,
    val caseId: String? = null,
    val agentName: String? = null,
    val idempotencyKey: String? = null,
    /**
     * Optional authoritative amendment counter the result was produced against
     * (Lot E compare-and-set). Accepted both top-level and inside `business`;
     * it is folded into the business payload so it is part of the semantic
     * identity of the result.
     */
    val expectedAmendmentSeq: Long? = null,
    val expected_amendment_seq: Long? = null,
)

/** The optional `observed` identity block of a submission request. */
data class ObservedIdentityRequest(
    val attemptId: String? = null,
    val caseId: String? = null,
    val agentName: String? = null,
)

/** Canonical HTTP success envelope: `{ "data": ... }`. */
data class AgentStepResultEnvelope<T>(
    val data: T,
)

/** Response payload of a result submission. */
data class AgentStepResultData(
    val resultId: String,
    val idempotent: Boolean,
    val resultHash: String,
)
