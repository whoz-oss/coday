package io.whozoss.agentos.sdk.api.exchange

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Response body for a successful exchange file deletion.
 *
 * Returned by `DELETE /api/cases/{caseId}/files` and
 * `DELETE /api/namespaces/{namespaceId}/files`.
 */
@Schema(name = "ExchangeDeleteResponse", description = "Result of an exchange file deletion.")
@JsonIgnoreProperties(ignoreUnknown = true)
data class ExchangeDeleteResponse(
    @field:Schema(description = "Whether the deletion succeeded.")
    val success: Boolean,
    @field:Schema(description = "Human-readable outcome message.")
    val message: String,
)
