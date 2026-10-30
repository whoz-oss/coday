package io.whozoss.agentos.sdk.util

/**
 * Shared string and collection utility functions.
 */
object StringUtils {

    /**
     * Filters out blank strings, trims all entries, and returns `null` if the resulting list is empty.
     * Returns `null` when this list is `null`.
     */
    fun List<String>?.nullOrNotBlankItems(): List<String>? =
        this?.filter { it.isNotBlank() }?.map { it.trim() }?.takeIf { it.isNotEmpty() }

    /**
     * Normalizes a relative file or resource path by trimming whitespace, stripping leading slashes,
     * and converting backslashes to forward slashes.
     */
    fun normalizeRelativePath(path: String): String =
        path.trim().trimStart('/').replace("\\", "/")
}
