package io.whozoss.agentos.exchange

/**
 * Entry names owned by a feature that runs inside Exchange scopes, not by their users.
 *
 * The Exchange file API never lists, reads or writes a path with such a segment, and the file
 * tools deny it. Features contribute their names as beans, so the Exchange itself knows none of
 * them. The rule is not configurable and holds in every scope.
 *
 * Names are matched against every path segment, ignoring case: a case-insensitive filesystem
 * would otherwise let a variant reach the same entry.
 */
fun interface ExchangeReservedEntries {
    fun names(): Set<String>
}

/** Whether [segment] is a name reserved by one of these contributions. */
internal fun List<ExchangeReservedEntries>.reserves(segment: String?): Boolean =
    segment != null && any { entries -> entries.names().any { it.equals(segment, ignoreCase = true) } }
