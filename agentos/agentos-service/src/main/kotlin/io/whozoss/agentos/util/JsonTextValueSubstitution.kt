package io.whozoss.agentos.util

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode

/**
 * Returns a new [JsonNode] with [substitute] applied to every textual value, recursively through objects and arrays.
 * Object keys, numbers, booleans and nulls are preserved. The input node is never mutated.
 */
fun substituteJsonTextValues(
    node: JsonNode?,
    substitute: (String) -> String,
): JsonNode? =
    when {
        node == null -> null
        node is TextNode -> TextNode.valueOf(substitute(node.textValue()))
        node is ObjectNode ->
            JsonNodeFactory.instance.objectNode().also { result ->
                node.properties().forEach { (key, value) -> result.set<JsonNode>(key, substituteJsonTextValues(value, substitute)) }
            }
        node is ArrayNode ->
            JsonNodeFactory.instance.arrayNode().also { result ->
                node.forEach { result.add(substituteJsonTextValues(it, substitute)) }
            }
        else -> node
    }
