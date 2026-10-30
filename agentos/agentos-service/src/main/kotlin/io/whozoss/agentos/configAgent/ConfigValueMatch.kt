package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Whether what the server stores already holds a value a plan proposes, the way the REST layer stores it:
 * - an object holds the proposed keys (the server may keep more, such as the fields a copy brings),
 *   and a key proposed as JSON null must be present (an integration granted with all its tools);
 * - an array holds the same elements in the same order;
 * - numbers compare by value (`10` and `10.0`), strings trimmed;
 * - an empty value (absent, JSON null, blank string) only matches an empty one.
 *
 * Paths in `skip` are not compared: a declared secret cannot be read back.
 */
internal object ConfigValueMatch {
    fun holds(
        stored: JsonNode?,
        proposed: JsonNode?,
        path: String,
        skip: Set<String> = emptySet(),
    ): Boolean =
        when {
            path in skip -> true
            proposed == null || isEmpty(proposed) -> isEmpty(stored)
            proposed is ObjectNode ->
                stored is ObjectNode && proposed.properties().all { (key, value) -> holdsMember(stored, key, value, childPath(path, key), skip) }
            proposed is ArrayNode ->
                stored is ArrayNode && stored.size() == proposed.size() && (0 until proposed.size()).all { holds(stored[it], proposed[it], "$path/$it", skip) }
            proposed.isNumber -> stored?.isNumber == true && stored.decimalValue().compareTo(proposed.decimalValue()) == 0
            proposed.isTextual -> stored?.isTextual == true && stored.textValue().trim() == proposed.textValue().trim()
            else -> stored == proposed
        }

    fun isEmpty(node: JsonNode?): Boolean = node == null || node.isNull || node.isMissingNode || (node.isTextual && node.textValue().isBlank())

    private fun holdsMember(
        stored: ObjectNode,
        key: String,
        proposed: JsonNode,
        path: String,
        skip: Set<String>,
    ): Boolean = if (proposed.isNull) stored.has(key) && stored.get(key).isNull else holds(stored.get(key), proposed, path, skip)

    private fun childPath(
        path: String,
        key: String,
    ): String = path + ConfigJson.pointerOf(listOf(key))
}
