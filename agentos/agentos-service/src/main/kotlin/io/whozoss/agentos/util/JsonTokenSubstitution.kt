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
 * applied: downstream consumers resolve `..` themselves. Tokens are applied in iteration order and
 * a value a token expands to is not rescanned, so one token's expansion can never be read as
 * another token.
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
): JsonNode? {
    if (node == null || tokens.isEmpty()) return node
    return when (node) {
        is TextNode -> TextNode.valueOf(substituteTokens(node.textValue(), tokens))
        is ObjectNode ->
            JsonNodeFactory.instance.objectNode().also { result ->
                node.fields().forEach { (key, value) -> result.set<JsonNode>(key, substituteJsonTokens(value, tokens)) }
            }

        is ArrayNode ->
            JsonNodeFactory.instance.arrayNode().also { result ->
                node.forEach { element -> result.add(substituteJsonTokens(element, tokens)) }
            }

        else -> node
    }
}

/** Replaces every occurrence of every key of [tokens] in [text] with its value. */
fun substituteTokens(
    text: String,
    tokens: Map<String, String>,
): String = tokens.entries.fold(text) { acc, (token, value) -> acc.replace(token, value) }
