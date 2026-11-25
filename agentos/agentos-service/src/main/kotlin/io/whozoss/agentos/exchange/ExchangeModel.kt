package io.whozoss.agentos.exchange

/**
 * Thrown when [ExchangeStorageService.writeNew] targets a path that already exists.
 * Callers map this to HTTP 409 Conflict.
 */
class FileExistsException(message: String) : RuntimeException(message)

/**
 * Thrown when an exchange relative path is blank, has an over-long segment, or escapes its
 * scope root (path traversal). Callers map this to HTTP 400 Bad Request.
 */
class InvalidExchangePathException(message: String) : RuntimeException(message)

/**
 * Thrown when a read/download targets a file larger than the configured read limit. Callers map
 * this to HTTP 422 Unprocessable Entity rather than loading it into memory.
 */
class ExchangeFileTooLargeException(message: String) : RuntimeException(message)

/**
 * Thrown when [ExchangeStorageService.writeContent] is given an etag that no longer matches the file
 * (it was modified since the editor opened it). Callers map this to HTTP 409 Conflict.
 */
class ExchangeEditConflictException(message: String) : RuntimeException(message)

/**
 * Body of the `PUT .../files/content` endpoints: the full new text of an existing file.
 *
 * [etag] is the one returned when the content was read; when present, the write is refused if the
 * file changed since. Lives in the service, not in the SDK, because the edit endpoints are not part
 * of the `ExchangeApi` contract (see `browseCaseFiles`).
 */
data class ExchangeFileUpdateRequest(
    val path: String,
    val content: String,
    val etag: String? = null,
)
