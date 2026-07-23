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
