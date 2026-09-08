package io.whozoss.agentos.sdk.api.case

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * Optional request body for the `POST /api/cases/{caseId}/read` endpoint.
 *
 * All fields are optional. An empty body `{}` is valid and means "mark as read at now".
 *
 * @property readAt When supplied, `readAt` on the `(User)-[:WATCHES]->(Case)` edge is set
 *   to this exact timestamp instead of the current server time. Useful for clients that
 *   want to back-date the read marker (e.g. when the user was looking at the case but
 *   the call was delayed). Must not be in the future — the service silently clamps to now
 *   if a future timestamp is supplied.
 */
@Schema(name = "MarkCaseReadRequest")
@JsonIgnoreProperties(ignoreUnknown = true)
data class MarkCaseReadRequest(
    @field:Schema(
        description = "Exact instant to record as the read timestamp. Defaults to server-side now() when absent.",
        nullable = true,
    )
    val readAt: Instant? = null,
)
