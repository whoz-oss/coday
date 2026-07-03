package io.whozoss.agentos.util

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
