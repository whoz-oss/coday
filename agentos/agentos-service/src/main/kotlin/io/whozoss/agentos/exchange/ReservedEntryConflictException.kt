package io.whozoss.agentos.exchange

/**
 * Thrown when a recursive folder delete would remove an entry reserved by a feature (see
 * [ExchangeReservedEntries]), such as the `.git` of a worktree. Nothing is deleted. Callers map this
 * to HTTP 409 Conflict.
 */
class ReservedEntryConflictException(message: String) : RuntimeException(message)
