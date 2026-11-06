package io.whozoss.agentos.configAgent

import tools.jackson.core.JsonPointer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.StringNode
import java.security.MessageDigest
import java.util.UUID

/** JSON helpers shared by the planner, the reader and the writer of the config agent. */
internal object ConfigJson {
    /** Value the user sees in place of a secret, and that the LLM may never send. */
    const val MASK = "****"

    /** Key of the object standing for the id created by another operation: `{"$op": "<ref>"}`. */
    const val OP_REF_KEY = "\$op"

    private val canonicalMapper = JsonMapper.builder().build()

    /** Compact JSON with object fields sorted, so equal values always hash the same. */
    fun canonical(node: JsonNode?): String = canonicalMapper.writeValueAsString(sorted(node ?: absent()))

    fun hash(node: JsonNode?): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical(node).toByteArray())
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    /** The value at [pointer], or null when the path does not exist. */
    fun valueAt(
        root: JsonNode,
        pointer: String,
    ): JsonNode? = root.at(JsonPointer.compile(pointer)).takeUnless { it.isMissingNode }

    /** Sets [value] at [pointer] in [root], creating the intermediate objects. */
    fun setAt(
        root: ObjectNode,
        pointer: String,
        value: JsonNode?,
    ) {
        val segments = segments(pointer)
        require(segments.isNotEmpty()) { "Empty path" }
        var current: ObjectNode = root
        for (segment in segments.dropLast(1)) {
            current =
                when (val next = current.get(segment)) {
                    is ObjectNode -> next
                    is ArrayNode -> throw IllegalArgumentException("'$pointer' goes through a list: replace the whole list instead")
                    else -> current.putObject(segment)
                }
        }
        current.set(segments.last(), value ?: JsonNodeFactory.instance.nullNode())
    }

    fun removeAt(
        root: ObjectNode,
        pointer: String,
    ) {
        val segments = segments(pointer)
        val parent = segments.dropLast(1).fold(root as JsonNode?) { node, segment -> node?.get(segment) }
        if (parent is ArrayNode) throw IllegalArgumentException("'$pointer' goes through a list: replace the whole list instead")
        (parent as? ObjectNode)?.remove(segments.last())
    }

    /** The JSON Pointer of [segments], escaped (`[integrations, ACME]` → `/integrations/ACME`). */
    fun pointerOf(segments: List<String>): String = segments.joinToString("") { "/" + it.replace("~", "~0").replace("/", "~1") }

    /** Decoded segments of a JSON Pointer (`/integrations/ACME` → `[integrations, ACME]`). */
    fun segments(pointer: String): List<String> {
        require(pointer.startsWith("/")) { "A path must start with '/': '$pointer'" }
        return pointer
            .substring(1)
            .split("/")
            .map { it.replace("~1", "/").replace("~0", "~") }
    }

    /** Refs named by `{"$op": "<ref>"}` objects anywhere in [node]. */
    fun opRefs(node: JsonNode?): Set<String> {
        if (node == null) return emptySet()
        opRefOf(node)?.let { return setOf(it) }
        return when (node) {
            is ObjectNode -> node.properties().flatMap { opRefs(it.value) }.toSet()
            is ArrayNode -> node.values().flatMap { opRefs(it) }.toSet()
            else -> emptySet()
        }
    }

    /** Writes [overrides] onto [target]: objects are merged key by key, anything else replaces the value. */
    fun merge(
        target: ObjectNode,
        overrides: ObjectNode,
    ) {
        overrides.properties().forEach { (key, value) ->
            val current = target.get(key)
            if (current is ObjectNode && value is ObjectNode) merge(current, value) else target.set(key, value.deepCopy())
        }
    }

    /** A copy of [node] where every `{"$op": "<ref>"}` is replaced by the id created for that ref. */
    fun resolveOpRefs(
        node: JsonNode?,
        createdIds: Map<String, UUID>,
    ): JsonNode? {
        if (node == null) return null
        opRefOf(node)?.let { ref ->
            val id = createdIds[ref] ?: throw IllegalStateException("Operation '$ref' has not created an entity yet")
            return StringNode.valueOf(id.toString())
        }
        return when (node) {
            is ObjectNode -> {
                JsonNodeFactory.instance.objectNode().also { copy ->
                    node.properties().forEach { (key, value) -> copy.set(key, resolveOpRefs(value, createdIds)) }
                }
            }

            is ArrayNode -> {
                JsonNodeFactory.instance.arrayNode().also { copy -> node.values().forEach { copy.add(resolveOpRefs(it, createdIds)) } }
            }

            else -> {
                node
            }
        }
    }

    fun containsMask(node: JsonNode?): Boolean =
        when (node) {
            null -> false
            is StringNode -> node.stringValue() == MASK
            is ObjectNode -> node.properties().any { containsMask(it.value) }
            is ArrayNode -> node.values().any { containsMask(it) }
            else -> false
        }

    /** The ref [node] names when it is a `{"$op": "<ref>"}` object, else null. */
    fun opRefOf(node: JsonNode): String? =
        if (node is ObjectNode && node.size() == 1 && node.get(OP_REF_KEY)?.isString == true) {
            node.get(OP_REF_KEY).stringValue()
        } else {
            null
        }

    private fun absent(): JsonNode = StringNode.valueOf("<absent>")

    /** Sorted fields, null fields dropped: a stored plan may lose its nulls on the way through persistence. */
    private fun sorted(node: JsonNode): JsonNode =
        when (node) {
            is ObjectNode -> {
                JsonNodeFactory.instance.objectNode().also { copy ->
                    node
                        .properties()
                        .filterNot { it.value.isNull }
                        .sortedBy { it.key }
                        .forEach { (key, value) -> copy.set(key, sorted(value)) }
                }
            }

            is ArrayNode -> {
                JsonNodeFactory.instance.arrayNode().also { copy -> node.values().forEach { copy.add(sorted(it)) } }
            }

            else -> {
                node
            }
        }
}
