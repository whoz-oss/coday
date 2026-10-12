package io.whozoss.agentos.util

import kotlin.math.min

/**
 * Maps each element through [transform], stopping after the first element for which
 * [predicate] returns false. The failing element's result is included in the output
 * so callers can inspect it (e.g. for error traces).
 */
suspend fun <T, R> Iterable<T>.mapWhile(
    transform: suspend (T) -> R,
    predicate: (T, R) -> Boolean,
): List<R> =
    buildList {
        for (item in this@mapWhile) {
            val result = transform(item)
            add(result)
            if (!predicate(item, result)) break
        }
    }
// rel

/**
 * return a sublist from 0 to [endExcluded]
 * Note : similar to take method but largely faster than that one
 */
fun <T> List<T>.subListTo(endExcluded: Int): List<T> = this.subList(0, endExcluded)

/**
 * The shortest way to take the first n element of a list
 */
fun <T> List<T>.tak(n: Int): List<T> = this.subList(0, min(this.size, n))

/**
 * Returns a diagnostic string with the first [count] elements of the list.
 *
 * @param count number of elements to show (default 10)
 * @param transform optional string representation for each element
 */
fun <T> List<T>.logFirsts(
    count: Int = 10,
    transform: ((T) -> String)? = null,
): String =
    if (transform != null) {
        "$count firsts are (for a total of ${this.size}): ${
            this.tak(count)
                .map { transform(it) }
        }"
    } else {
        "$count firsts are (for a total of ${this.size}): ${this.tak(count)}"
    }

/**
 * return a sublist from [start] to end of list
 */
fun <T> List<T>.subListFrom(start: Int): List<T> = this.subList(start, this.size)

/**
 * check if one element is inside both list
 */
fun <T> Collection<T>.containsAny(other: Collection<T>): Boolean = this.any { other.contains(it) }

fun <T> MutableCollection<T>.removeAll(vararg args: T) {
    args.forEach { this.remove(it) }
}

/**
 * make operation between an element and the one before
 */
fun <T, R> List<T>.mapPrevious(transform: (previous: T, T) -> R): List<R> {
    if (this.size <= 1) throw IllegalArgumentException("List should be at least of size two")
    return this.indices
        .toList()
        .subListFrom(1)
        .map {
            transform(this[it - 1], this[it])
        }
}

/**
 * get all indexes that return true to [predicate]
 */
inline fun <T> Iterable<T>.indexesOf(predicate: (T) -> Boolean): List<Int> =
    this
        .withIndex()
        .filter { predicate(it.value) }
        .map { it.index }

fun <T> List<T>.anyOrNull(filter: (T) -> Boolean): Boolean? =
    if (this.any {
            filter(it)
        }
    ) {
        true
    } else {
        null
    }

/**
 * Returns the first [n] elements of the set, preserving insertion order.
 * Uses a pre-allocated [LinkedHashSet] to avoid over-allocation.
 */
fun <T> Set<T>.tak(n: Int): Set<T> {
    val result = LinkedHashSet<T>(min(n, this.size))
    val iter = this.iterator()
    var i = 0
    while (i < n && iter.hasNext()) {
        result.add(iter.next())
        i++
    }
    return result
}

/**
 * Returns a diagnostic string with the first [count] elements of the set.
 * Delegates truncation to [Set.tak].
 *
 * @param count number of elements to show (default 10)
 * @param transform optional string representation for each element
 */
fun <T> Set<T>.logFirsts(
    count: Int = 10,
    transform: ((T) -> String)? = null,
): String =
    if (transform != null) {
        "$count firsts are (for a total of ${this.size}): ${
            this.tak(count)
                .map { transform(it) }
        }"
    } else {
        "$count firsts are (for a total of ${this.size}): ${this.tak(count)}"
    }
