package io.whozoss.agentos.util

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode

/**
 * Returns a new [JsonNode] with every key of [tokens] replaced by its value in every text node,
 * recursively through objects and arrays. Numbers, booleans and nulls are preserved as-is.
 *
 * The input [node] is never mutated — a fresh tree is built, so a cached original stays safe to
 * share. Returns [node] unchanged when it is null or [tokens] is empty, which also keeps the common
 * "nothing to substitute" case free of an allocation.
 *
 * Substitution is a plain textual replacement, positional and repeatable within a value (e.g.
 * `--config={{SOME_TOKEN}}/settings.json`), never a prefix-only rule, and no normalization is
 * applied: downstream consumers resolve `..` themselves. Tokens are applied in iteration order, one
 * full pass each, so a later token *is* matched against what an earlier one expanded to: with
 * `A -> "x/{{B}}/y"` and `B -> "z"`, `{{A}}` yields `x/z/y`. Do not rely on an expansion being
 * left alone; the exchange tokens expand to filesystem paths, which cannot contain a `{{TOKEN}}`
 * spelling unless the configured mount root does.
 *
 * Two independent callers use this with different lifecycles, and they must not be confused:
 * [io.whozoss.agentos.plugin.filesystem.substituteConfigPath] resolves `{{NAMESPACE_CONFIG_PATH}}`
 * once, when a filesystem YAML config is loaded, while
 * [io.whozoss.agentos.exchange.ExchangePathRunIntegration] resolves the exchange tokens per agent
 * run, for configs of every origin.
 */
fun substituteJsonTokens(
    node: JsonNode?,
    tokens: Map<String, String>,
): JsonNode? =
    when {
        node == null || tokens.isEmpty() -> node
        node is TextNode -> TextNode.valueOf(substituteTokens(node.textValue(), tokens))
        node is ObjectNode ->
            JsonNodeFactory.instance.objectNode().also { result ->
                node.properties().forEach { (key, value) -> result.set<JsonNode>(key, substituteJsonTokens(value, tokens)) }
            }

        node is ArrayNode ->
            JsonNodeFactory.instance.arrayNode().also { result ->
                node.forEach { element -> result.add(substituteJsonTokens(element, tokens)) }
            }

        else -> node
    }

/**
 * Which of [candidates] appear in a text node of [node], at any depth.
 *
 * The cheap question a caller asks before resolving what a token stands for: that resolution can
 * cost database reads, and most configs carry no token at all. One walk answers for every candidate
 * at once, and nothing is serialized. The early exit compares sizes, which is why [candidates] is a
 * set: a list with a repeated token would cap [found] below it and disable the exit for good. It
 * only fires once every candidate has been seen,
 * so the common no-token case does traverse the whole node — an integration may hold a whole inline
 * OpenAPI document in its parameters. That traversal is in-memory and bounded by a tree the caller
 * has just deserialized from the database anyway; what it buys is never resolving a token nobody
 * wrote.
 */
fun jsonTokensPresent(
    node: JsonNode?,
    candidates: Set<String>,
): Set<String> {
    val found = mutableSetOf<String>()
    collectTokens(node, candidates, found)
    return found
}

private fun collectTokens(
    node: JsonNode?,
    candidates: Set<String>,
    found: MutableSet<String>,
) {
    when {
        node == null || found.size == candidates.size -> Unit
        node.isTextual -> {
            val text = node.textValue()
            candidates.forEach { token -> if (text.contains(token)) found.add(token) }
        }
        // Iterating a JsonNode yields an object's values and an array's elements alike.
        node.isContainerNode -> node.forEach { child -> collectTokens(child, candidates, found) }
        else -> Unit
    }
}

/** Replaces every occurrence of every key of [tokens] in [text] with its value. */
fun substituteTokens(
    text: String,
    tokens: Map<String, String>,
): String = tokens.entries.fold(text) { acc, (token, value) -> acc.replace(token, value) }
