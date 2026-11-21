package io.whozoss.agentos.exchange

/**
 * Thrown when a folder deletion would remove an entry reserved by a feature (see [ExchangeReservedEntries]),
 * such as a Git worktree's `.git`. Nothing is deleted. Callers map this to HTTP 409 Conflict.
 */
class ReservedEntryConflictException(message: String) : RuntimeException(message)
