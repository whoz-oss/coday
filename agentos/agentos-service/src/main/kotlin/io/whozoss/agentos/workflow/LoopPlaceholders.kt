package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.TextNode

/**
 * Resolves the `${loopData...}` placeholders of a loop step against the `loopData` of the run.
 *
 * ## Syntax
 *
 * `${loopData}` or `${loopData.k1.k2...}`: dot-separated keys, each matching `[A-Za-z_][A-Za-z0-9_-]*`. There is
 * no array index, operator, default value or escaping, and the only root is `loopData` (`${steps...}` is kept
 * free for a later extension). Any `${` that does not open a full, valid placeholder is an error.
 *
 * ## Where and how
 *
 * [resolve] walks a template and substitutes in every string value, at any depth of objects and arrays; object
 * keys are never touched.
 *
 * - A string that is exactly one placeholder is replaced by the JSON value as it is, type preserved (object,
 *   array, number, boolean, null or string).
 * - A placeholder inside a longer string is interpolated as text, which only scalar values (string, number,
 *   boolean) allow; an object, an array or a null there is an error.
 * - What is inserted is never scanned again: a `${...}` found in the *value* of `loopData` stays literal.
 *
 * The template and `loopData` are left untouched: the result is a deep copy.
 *
 * Failures are [IllegalArgumentException]s whose message starts with the location of the offending value, so
 * that a caller can tell the user which step and which argument is wrong.
 */
object LoopPlaceholders {
    /** The only root a placeholder can start from. */
    const val ROOT = "loopData"

    private const val KEY = "[A-Za-z_][A-Za-z0-9_-]*"
    private const val OPENING = "\${"

    /** A full placeholder; group 1 holds its keys, with their leading dots (empty for the root). */
    private val PLACEHOLDER = Regex("\\\$\\{$ROOT((?:\\.$KEY)*)\\}")

    /**
     * A copy of [template] with its placeholders resolved against [loopData].
     *
     * @param location Where [template] comes from, such as `step 'search' args`; prefixes error messages and is
     *   extended with the JSON path of the offending value.
     * @throws IllegalArgumentException when a key is missing from [loopData], a non-scalar is interpolated into a
     *   longer string, or a placeholder is malformed.
     */
    fun resolve(
        template: JsonNode,
        loopData: JsonNode,
        location: String = "",
    ): JsonNode = resolveNode(template, loopData, location)

    private fun resolveNode(
        node: JsonNode,
        loopData: JsonNode,
        path: String,
    ): JsonNode =
        when {
            node.isTextual -> {
                resolveText(node.textValue(), loopData, path)
            }

            node.isObject -> {
                val result = JsonNodeFactory.instance.objectNode()
                node.fieldNames().forEach { name ->
                    result.replace(name, resolveNode(node.get(name), loopData, if (path.isEmpty()) name else "$path.$name"))
                }
                result
            }

            node.isArray -> {
                val result = JsonNodeFactory.instance.arrayNode()
                node.forEachIndexed { index, child -> result.add(resolveNode(child, loopData, "$path[$index]")) }
                result
            }

            else -> {
                node.deepCopy<JsonNode>()
            }
        }

    private fun resolveText(
        text: String,
        loopData: JsonNode,
        path: String,
    ): JsonNode {
        val whole = PLACEHOLDER.matchEntire(text)
        return when {
            whole != null -> lookup(whole.groupValues[1], loopData, path).deepCopy<JsonNode>()
            else -> TextNode.valueOf(interpolate(text, loopData, path))
        }
    }

    private fun interpolate(
        text: String,
        loopData: JsonNode,
        path: String,
    ): String {
        requireWellFormed(text, path)
        return PLACEHOLDER.replace(text) { match ->
            val value = lookup(match.groupValues[1], loopData, path)
            when {
                value.isTextual || value.isNumber || value.isBoolean -> value.asText()
                else ->
                    fail(
                        path,
                        "'${match.value}' is ${describe(value)}, which cannot be embedded in the text '$text'; " +
                            "use it as the whole value to keep its type",
                    )
            }
        }
    }

    /** Every `${` of [text] must open a full placeholder. */
    private fun requireWellFormed(
        text: String,
        path: String,
    ) {
        var index = text.indexOf(OPENING)
        while (index >= 0) {
            if (PLACEHOLDER.find(text, index)?.range?.first != index) {
                fail(
                    path,
                    "malformed placeholder at position $index of '$text': only \${$ROOT} and " +
                        "\${$ROOT.key.key} are supported, with keys made of letters, digits, '_' and '-'",
                )
            }
            index = text.indexOf(OPENING, index + 1)
        }
    }

    private fun lookup(
        dottedKeys: String,
        loopData: JsonNode,
        path: String,
    ): JsonNode {
        var current = loopData
        val walked = StringBuilder(ROOT)
        dottedKeys.split('.').filter { it.isNotEmpty() }.forEach { key ->
            walked.append('.').append(key)
            current = current.get(key) ?: fail(path, "'$walked' is not defined in loopData")
        }
        return current
    }

    private fun describe(value: JsonNode): String =
        when {
            value.isNull -> "null"
            value.isObject -> "an object"
            value.isArray -> "an array"
            else -> "a ${value.nodeType.name.lowercase()}"
        }

    private fun fail(
        path: String,
        message: String,
    ): Nothing = throw IllegalArgumentException(if (path.isBlank()) message else "$path: $message")
}
