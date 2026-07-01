package io.whozoss.agentos.exchange

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * Scope of an exchange file: case-private or namespace-shared.
 */
enum class ExchangeScope {
    /** Files private to a single case (`<mountRoot>/<namespaceId>/cases/<caseId>`). */
    CASE,

    /** Files shared across a namespace (`<mountRoot>/<namespaceId>/shared`). */
    NAMESPACE,
}

/**
 * Capability an actor (agent or user) has over an exchange scope.
 *
 * Ordered from least to most permissive so callers may compare ordinals.
 */
enum class ExchangeCapability {
    /** No access. */
    NONE,

    /** Read and list only. */
    READ,

    /** Read, list, write and delete. */
    READ_WRITE,
}

/**
 * Metadata describing a single file within an exchange scope.
 */
@Schema(name = "ExchangeFileEntry", description = "Metadata for a single file in an exchange scope.")
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

/**
 * Listing of files in an exchange scope together with the caller's capability over it.
 */
@Schema(name = "ExchangeManifest", description = "Files in an exchange scope plus the caller's capability.")
data class ExchangeManifest(
    @field:Schema(description = "Files available in the scope.")
    val files: List<ExchangeFileEntry>,
    @field:Schema(description = "Capability the caller has over this scope.")
    val capability: ExchangeCapability,
)

/**
 * Text content of a single exchange file plus its metadata.
 */
@Schema(name = "ExchangeFileContent", description = "Text content and metadata of a single exchange file.")
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

/**
 * Thrown when [ExchangeStorageService.writeNew] targets a path that already exists.
 * Callers map this to HTTP 409 Conflict.
 */
class FileExistsException(message: String) : RuntimeException(message)
