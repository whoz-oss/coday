package io.whozoss.agentos.sdk.api.exchange

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Text content of a single exchange file plus its metadata.
 *
 * Returned by `GET /api/cases/{caseId}/files/content` and
 * `GET /api/namespaces/{namespaceId}/files/content`.
 *
 * The server rejects non-UTF-8 files with 400; callers can always treat [content] as valid text.
 */
@Schema(name = "ExchangeFileContent", description = "Text content and metadata of a single exchange file.")
@JsonIgnoreProperties(ignoreUnknown = true)
data class ExchangeFileContent(
    @field:Schema(description = "UTF-8 text content of the file.")
    val content: String,
    @field:Schema(description = "Opaque entity tag derived from size and last-modified time.")
    val etag: String? = null,
    @field:Schema(description = "Detected MIME type, or null if it could not be determined.")
    val mimeType: String? = null,
    @field:Schema(description = "File size in bytes.")
    val size: Long,
)
