package io.whozoss.factory.agentattempt.persistence

import io.whozoss.factory.persistence.TenantScope

/** A cached idempotent request/response pair (V4 `idempotency_records`). */
data class IdempotencyRecord(
    val requestHash: String,
    val responsePayload: String,
    val status: String,
)

/**
 * Persistence port of the V4 `idempotency_records` dedupe/response cache.
 *
 * The record is keyed by `(organization_id, idempotency_key)`; `request_hash`
 * fingerprints the request so a replay with a divergent body is rejected while
 * an identical replay is answered from `response_payload`.
 */
interface IdempotencyRepository {

    /** The record stored for [idempotencyKey] in the caller's organization. */
    fun find(scope: TenantScope, idempotencyKey: String): IdempotencyRecord?

    /** Store the fingerprint and cached response of a completed request. */
    fun save(scope: TenantScope, idempotencyKey: String, requestHash: String, responsePayload: String)
}
