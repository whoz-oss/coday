package io.whozoss.agentos.sdk.api.exchange

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * One entry of a single directory level: a file, or a sub-directory to descend into.
 *
 * Unlike [ExchangeFileEntry], which only ever describes a file found by a recursive walk, this
 * represents what a file browser shows at one level — which is why directories appear here at all.
 */
@Schema(name = "ExchangeDirectoryEntry", description = "A file or sub-directory at one level of an exchange scope.")
@JsonIgnoreProperties(ignoreUnknown = true)
data class ExchangeDirectoryEntry(
    @field:Schema(description = "Path relative to the exchange root, using forward slashes.")
    val path: String,
    @field:Schema(description = "Entry name (last path segment).")
    val name: String,
    @field:Schema(description = "True for a sub-directory, false for a file.")
    val directory: Boolean,
    @field:Schema(description = "File size in bytes; null for a directory, whose size is not computed.")
    val size: Long? = null,
    @field:Schema(description = "Last modification timestamp.")
    val lastModified: Instant? = null,
    @field:Schema(description = "Detected MIME type for a file, null otherwise.")
    val mimeType: String? = null,
)
