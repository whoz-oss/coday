package io.whozoss.agentos.plugins.http.response

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Filters a JSON response with dot-notation paths carrying `*` wildcards, the model of the Coday TypeScript
 * HTTP integration (`filterResponse` in `libs/integrations/http`):
 *
 * - `keepPaths` wins: when set, only the matching paths are kept; otherwise `ignorePaths` removes its matches;
 * - arrays are transparent: a path applies to each element (`results.id` filters every result);
 * - `*` expands to every key of an object and, applied to an array, to each of its elements
 *   (`results.*.id`, the reading documented for the TypeScript filter);
 * - a repeated head (`ticket.id`, `ticket.status`) merges into the already kept object, through arrays too
 *   (`results.id`, `results.subject` keep both fields of every result; the TypeScript code lets the last
 *   path win there): the paths are grouped by key before recursing, so objects and arrays behave alike;
 * - a primitive reached with path segments left is kept as is, a missing key is skipped.
 */
object PathFilter {

    fun filter(node: JsonNode, keepPaths: List<String>, ignorePaths: List<String>): JsonNode =
        when {
            keepPaths.isNotEmpty() -> keep(node, keepPaths.map(::segments))
            ignorePaths.isNotEmpty() -> ignore(node, ignorePaths.map(::segments))
            else -> node
        }

    private fun segments(path: String): List<String> = path.split('.')

    private fun keep(node: JsonNode, paths: List<List<String>>): JsonNode =
        when {
            node.isArray -> perElement(node, paths, Mode.KEEP)
            node.isObject -> keepObject(node, paths)
            else -> node
        }

    /** Every path is first resolved to the keys it addresses, so one key is kept once with all its tails. */
    private fun keepObject(node: JsonNode, paths: List<List<String>>): ObjectNode {
        val tailsByKey = paths
            .filter { it.first().isNotEmpty() }
            .flatMap { path -> keysFor(path.first(), node).map { key -> key to path.drop(1) } }
            .groupBy({ it.first }, { it.second })
        val result = JsonNodeFactory.instance.objectNode()
        tailsByKey.forEach { (key, tails) ->
            val value = node.get(key) ?: return@forEach
            result.set<JsonNode>(key, if (tails.any { it.isEmpty() }) value.deepCopy() else keep(value, tails))
        }
        return result
    }

    private fun ignore(node: JsonNode, paths: List<List<String>>): JsonNode =
        when {
            node.isArray -> perElement(node, paths, Mode.IGNORE)
            node.isObject -> ignoreObject(node, paths)
            else -> node
        }

    private fun ignoreObject(node: JsonNode, paths: List<List<String>>): ObjectNode {
        val result = (node as ObjectNode).deepCopy()
        paths.filter { it.first().isNotEmpty() }.forEach { path ->
            val head = path.first()
            val tail = path.drop(1)
            keysFor(head, result).forEach { key ->
                val value = result.get(key) ?: return@forEach
                if (tail.isEmpty()) result.remove(key) else result.set<JsonNode>(key, ignore(value, listOf(tail)))
            }
        }
        return result
    }

    /**
     * Filters every element of [array] with the paths adjusted for the array: a `*` head is consumed
     * (it stands for the element), a `*` head with nothing after it selects the whole element, any other path
     * is passed through unchanged.
     */
    private fun perElement(array: JsonNode, paths: List<List<String>>, mode: Mode): JsonNode {
        val elementPaths = paths.map { path -> if (path.first() == WILDCARD) path.drop(1) else path }
        if (elementPaths.any { it.isEmpty() }) return wholeElements(array, mode)
        val result = JsonNodeFactory.instance.arrayNode()
        array.forEach { element ->
            result.add(if (mode == Mode.KEEP) keep(element, elementPaths) else ignore(element, elementPaths))
        }
        return result
    }

    /** `items.*` keeps every element as is, or removes them all when ignoring. */
    private fun wholeElements(array: JsonNode, mode: Mode): ArrayNode =
        if (mode == Mode.KEEP) (array as ArrayNode).deepCopy() else JsonNodeFactory.instance.arrayNode()

    private enum class Mode { KEEP, IGNORE }

    private fun keysFor(head: String, node: JsonNode): List<String> =
        if (head == WILDCARD) node.properties().map { it.key } else listOf(head)

    private const val WILDCARD = "*"
}
