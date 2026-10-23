package io.whozoss.factory.agentattempt.web

/**
 * Request body of `POST /api/factory/step-result-bindings`.
 *
 * The capability bearer token may be presented either in the body
 * ([capabilityToken]) or as an `Authorization: Bearer <token>` header — exactly
 * like the submission endpoint. All fields are nullable so a malformed body
 * surfaces as an explicit 4xx rather than a deserialization failure.
 */
data class FactoryStepResultBindingRequest(
    val capabilityToken: String? = null,
    val attemptId: String? = null,
)

/** Canonical HTTP success envelope: `{ "data": ... }`. */
data class FactoryStepResultBindingEnvelope<T>(
    val data: T,
)

/**
 * Non-sensitive projection of an issued step-result capability: the identity the
 * token is bound to, never the token digest itself.
 */
data class FactoryStepResultBindingData(
    val attemptId: String,
    val workflowId: String,
    val stepId: String,
    val namespaceId: String,
    val caseId: String,
    val agentName: String,
    val issuedAt: String,
    val expiresAt: String,
    val submissionBudget: Int,
    val verified: Boolean = true,
)
