package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.sdk.caseEvent.CaseEvent

/**
 * Inserts [event] into this chronologically-sorted list while maintaining ascending timestamp order.
 *
 * Events with equal timestamps are inserted **after** all existing events with the same timestamp,
 * preserving insertion-order stability.
 *
 * Uses binary search for O(log n) index discovery. The list shift on insertion is still O(n),
 * but the scan itself no longer touches every element.
 *
 * ### Binary search contract
 * The comparator returns:
 * - negative  → existing element is ≤ target  → search right (keep going toward higher indices)
 * - positive  → existing element is strictly > target  → search left
 *
 * By never returning 0, [binarySearch] always yields a negative value `-(insertionPoint + 1)`,
 * where `insertionPoint` is the index of the first element strictly greater than [event].
 * Inserting at that point places [event] after all equal-timestamp predecessors.
 */
internal fun MutableList<CaseEvent>.insertChronologically(event: CaseEvent) {
    val raw = binarySearch { existing ->
        if (existing.timestamp > event.timestamp) 1 else -1
    }
    // raw is always negative because the comparator never returns 0.
    // insertionPoint = -raw - 1  →  first index where timestamp is strictly greater.
    add(-raw - 1, event)
}
