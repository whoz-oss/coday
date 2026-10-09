package io.whozoss.factory.oracle.web

import io.whozoss.factory.oracle.service.OracleRunResult

/**
 * Request body of `POST .../oracles/{oracleId}/runs`.
 *
 * Port of the Node body contract: only `namespaceId` (required) and an optional
 * `idempotencyKey` are accepted. Fields are nullable so a malformed body surfaces
 * as a 400 `INVALID_ORACLE_RUN_REQUEST` rather than a deserialization failure.
 */
data class OracleRunRequest(
    val namespaceId: String? = null,
    val idempotencyKey: String? = null,
)

/** Canonical HTTP success envelope: `{ "data": ... }`. */
data class DataEnvelope<T>(
    val data: T,
)

/** Response payload of an oracle run. */
data class OracleRunResponse(
    val workflowId: String,
    val stepId: String,
    val oracleId: String,
    val executionId: String,
    val status: String,
    val revision: Int,
    val outcome: String,
    val evidenceId: String?,
    val artifactId: String?,
    val created: Boolean,
    val idempotent: Boolean,
)

fun OracleRunResult.toResponse(): OracleRunResponse = OracleRunResponse(
    workflowId = workflowId,
    stepId = stepId,
    oracleId = oracleId,
    executionId = executionId,
    status = status.name,
    revision = revision,
    outcome = outcome,
    evidenceId = evidenceId,
    artifactId = artifactId,
    created = created,
    idempotent = idempotent,
)
