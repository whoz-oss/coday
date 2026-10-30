package io.whozoss.factory.error

/**
 * Canonical HTTP error envelope retained from the former Node Factory HTTP
 * boundary contract:
 *
 * ```json
 * { "error": { "code": "NOT_FOUND", "message": "...", "details": null } }
 * ```
 *
 * `details` is always serialized — even when `null` — so the wire shape is
 * identical to the Node `send`/`normalizeErrorBody` output.
 */
data class ErrorResponse(
    val error: ErrorDetail,
)

data class ErrorDetail(
    val code: String,
    val message: String,
    val details: Any? = null,
)
