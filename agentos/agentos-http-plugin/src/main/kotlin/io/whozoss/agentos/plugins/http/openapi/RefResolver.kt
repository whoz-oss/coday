package io.whozoss.agentos.plugins.http.openapi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Inlines local `#/...` references of an OpenAPI document into a self-contained copy of a node.
 *
 * - A reference already being expanded (cycle) or nested deeper than [MAX_DEPTH] becomes
 *   `{"type":"object","description":"<name> (recursive)"}`.
 * - A reference that does not start with `#` is external and is never fetched: it raises
 *   [UnresolvableRefException], as does a local pointer with no target.
 * - Sibling keys of `$ref` (allowed by OpenAPI 3.1) overlay the resolved target.
 */
class RefResolver(private val root: JsonNode) {

    fun inline(node: JsonNode): JsonNode = inline(node, activeRefs = emptyList())

    /** The node a local [ref] points to, not inlined; null when the reference is external or has no target. */
    fun localTarget(ref: String): JsonNode? {
        if (!ref.startsWith("#")) return null
        return root.at(ref.removePrefix("#")).takeUnless { it.isMissingNode }
    }

    private fun inline(node: JsonNode, activeRefs: List<String>): JsonNode =
        when {
            node.isObject && node.has(REF) -> inlineRef(node as ObjectNode, activeRefs)
            node.isObject -> copyObject(node as ObjectNode, activeRefs)
            node.isArray -> copyArray(node as ArrayNode, activeRefs)
            else -> node
        }

    private fun inlineRef(node: ObjectNode, activeRefs: List<String>): JsonNode {
        val ref = node.get(REF).asText()
        if (!ref.startsWith("#")) {
            throw UnresolvableRefException(ref = ref, reason = "external references are not supported")
        }
        if (ref in activeRefs || activeRefs.size >= MAX_DEPTH) return placeholder(ref)
        val target = localTarget(ref) ?: throw UnresolvableRefException(ref = ref, reason = "no such component")
        val resolved = inline(target, activeRefs + ref)
        return overlaySiblings(resolved, node, activeRefs)
    }

    private fun overlaySiblings(resolved: JsonNode, refNode: ObjectNode, activeRefs: List<String>): JsonNode {
        val siblings = refNode.properties().filter { it.key != REF }
        if (siblings.isEmpty() || !resolved.isObject) return resolved
        val merged = (resolved as ObjectNode).deepCopy()
        siblings.forEach { (key, value) -> merged.set<JsonNode>(key, inline(value, activeRefs)) }
        return merged
    }

    private fun copyObject(node: ObjectNode, activeRefs: List<String>): ObjectNode {
        val copy = JsonNodeFactory.instance.objectNode()
        node.properties().forEach { (key, value) -> copy.set<JsonNode>(key, inline(value, activeRefs)) }
        return copy
    }

    private fun copyArray(node: ArrayNode, activeRefs: List<String>): ArrayNode {
        val copy = JsonNodeFactory.instance.arrayNode()
        node.forEach { copy.add(inline(it, activeRefs)) }
        return copy
    }

    private fun placeholder(ref: String): ObjectNode =
        JsonNodeFactory.instance.objectNode()
            .put("type", "object")
            .put("description", "${ref.substringAfterLast('/')} (recursive)")

    companion object {
        const val MAX_DEPTH = 8
        private const val REF = "\$ref"
    }
}

/** Raised when a `$ref` cannot be inlined; the containing operation must be skipped. */
class UnresolvableRefException(val ref: String, reason: String) :
    RuntimeException("Cannot resolve \$ref '$ref': $reason")
