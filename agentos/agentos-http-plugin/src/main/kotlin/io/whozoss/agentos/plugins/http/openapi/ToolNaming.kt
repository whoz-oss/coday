package io.whozoss.agentos.plugins.http.openapi

import java.security.MessageDigest

/**
 * Derives tool names of the form `<configName>__<suffix>`.
 *
 * The full name must match `^[A-Za-z0-9_-]{1,64}$` (the strictest provider rule, Anthropic/OpenAI)
 * and the suffix must never contain `__`: the service splits the integration prefix on the first `__`.
 * The suffix is sanitised here; the integration name is not rewritten but checked by [configNameProblem],
 * which the tool provider consults before exposing anything.
 *
 * A suffix that does not fit is truncated and its last characters replaced by `_` plus the first four
 * hex digits of the SHA-256 of the original suffix, so two long ids stay distinct.
 */
object ToolNaming {

    /** Suffix of an operation: its sanitised operationId, else `<method>_<path>` with `{x}` segments as `by_x`. */
    fun suffixFor(operationId: String?, method: HttpMethod, path: String): String =
        operationId?.let { sanitise(it) }?.takeIf { it.isNotEmpty() } ?: pathSuffix(method, path)

    private fun pathSuffix(method: HttpMethod, path: String): String {
        val pathPart = PATH_PARAMETER.replace(path) { "by_${it.groupValues[1]}" }.replace('/', '_')
        return sanitise("${method.name.lowercase()}_$pathPart")
    }

    /**
     * Full tool name, with the suffix shortened when `<configName>__<suffix>` would exceed [MAX_NAME_LENGTH].
     *
     * [configName] must leave room for the separator plus a hashed suffix, i.e. be at most
     * [MAX_CONFIG_NAME_LENGTH] characters long. A longer name fails with [IllegalArgumentException], a
     * programming error rather than a user-facing validation: the plugin's tool provider refuses the
     * names [configNameProblem] reports before curation runs.
     */
    fun toolName(configName: String, suffix: String): String = "$configName$SEPARATOR${fit(configName, suffix)}"

    /**
     * Why [configName] cannot prefix a tool name, or null when it can: it must match `[A-Za-z0-9_-]+`, be
     * free of the `__` separator and be at most [MAX_CONFIG_NAME_LENGTH] characters long. The service
     * only requires an integration name to be non-blank, so the plugin refuses the rest itself.
     */
    fun configNameProblem(configName: String): String? =
        when {
            configName.length > MAX_CONFIG_NAME_LENGTH ->
                "integration name is too long: at most $MAX_CONFIG_NAME_LENGTH characters are allowed"
            !CONFIG_NAME.matches(configName) ->
                "integration name must contain only letters, digits, '_' and '-' to prefix tool names"
            configName.contains(SEPARATOR) ->
                "integration name must not contain '$SEPARATOR', the separator between the integration and " +
                    "the operation"
            else -> null
        }

    /**
     * Suffixes for [rawSuffixes] (same order) that all fit the length limit and are unique inside the
     * catalogue: a repeated suffix gets `_2`, `_3`, ... appended.
     */
    fun assignUniqueSuffixes(configName: String, rawSuffixes: List<String>): List<String> {
        val taken = mutableSetOf<String>()
        return rawSuffixes.map { raw ->
            val suffix = firstFreeSuffix(configName, raw, taken)
            taken += suffix
            suffix
        }
    }

    /** The first of `raw`, `raw_2`, `raw_3`, ... that, once fitted to the length limit, is not in [taken]. */
    private fun firstFreeSuffix(configName: String, raw: String, taken: Set<String>): String {
        val candidates = sequenceOf(raw) + generateSequence(2) { it + 1 }.map { "${raw}_$it" }
        return candidates.map { fit(configName, it) }.first { it !in taken }
    }

    private fun sanitise(raw: String): String =
        UNDERSCORE_RUN.replace(FORBIDDEN.replace(raw, "_"), "_").trim('_')

    private fun fit(configName: String, suffix: String): String {
        require(configName.length <= MAX_CONFIG_NAME_LENGTH) {
            "Integration name '$configName' is too long to derive tool names within $MAX_NAME_LENGTH characters"
        }
        val available = MAX_NAME_LENGTH - configName.length - SEPARATOR.length
        if (suffix.length <= available) return suffix
        val kept = suffix.take(available - HASH_SUFFIX_LENGTH).trimEnd('_')
        return "${kept}_${hash(suffix)}"
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(HASH_LENGTH / 2)
            .joinToString("") { "%02x".format(it) }

    const val MAX_NAME_LENGTH = 64
    private const val SEPARATOR = "__"
    private const val HASH_LENGTH = 4
    private const val HASH_SUFFIX_LENGTH = HASH_LENGTH + 1

    /** Longest integration name that leaves room for `__`, one character and a hashed suffix. */
    const val MAX_CONFIG_NAME_LENGTH = MAX_NAME_LENGTH - SEPARATOR.length - HASH_SUFFIX_LENGTH - 1
    private val FORBIDDEN = Regex("[^A-Za-z0-9_-]")
    private val CONFIG_NAME = Regex("[A-Za-z0-9_-]+")
    private val UNDERSCORE_RUN = Regex("_+")
    private val PATH_PARAMETER = Regex("\\{([^}]*)}")
}
