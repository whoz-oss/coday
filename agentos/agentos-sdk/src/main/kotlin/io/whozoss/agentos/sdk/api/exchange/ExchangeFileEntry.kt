package io.whozoss.agentos.sdk.api.exchange

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * Metadata describing a single file within an exchange scope.
 *
 * Returned by the manifest endpoints and as the body of a successful upload.
 */
@Schema(name = "ExchangeFileEntry", description = "Metadata for a single file in an exchange scope.")
@JsonIgnoreProperties(ignoreUnknown = true)
data class ExchangeFileEntry(
    @field:Schema(description = "Path of the file relative to its exchange root, using forward slashes.")
    val path: String,
    @field:Schema(description = "File name (last path segment).")
    val filename: String,
    @field:Schema(description = "File size in bytes.")
    val size: Long,
    @field:Schema(description = "Last modification timestamp.")
    val lastModified: Instant,
    @field:Schema(description = "Detected MIME type, or null if it could not be determined.")
    val mimeType: String? = null,
    @field:Schema(description = "Scope the file belongs to.")
    val scope: ExchangeScope,
    @field:Schema(description = "Opaque entity tag derived from size and last-modified time.")
    val etag: String? = null,
)
