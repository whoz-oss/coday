package io.whozoss.agentos.sdk.tool

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Builds the hand-written JSON Schemas (draft 2020-12) of tool inputs with Jackson, so descriptions are escaped safely.
 *
 * ```kotlin
 * override val inputSchema: String =
 *     ToolSchema.schema(
 *         required = listOf("files"),
 *         properties = mapOf("files" to ToolSchema.stringArray(description = "Files to process")),
 *     )
 * ```
 */
object ToolSchema {
    private val mapper = jacksonObjectMapper()

    /** A `string` property. */
    fun string(description: String): ObjectNode = typed(type = "string", description = description)

    /** An `integer` property. */
    fun integer(description: String): ObjectNode = typed(type = "integer", description = description)

    /** An `array` property of strings. */
    fun stringArray(description: String): ObjectNode =
        typed(type = "array", description = description).also { it.putObject("items").put("type", "string") }

    /** An `array` property of objects with the given [itemProperties], [itemRequired] ones being mandatory. */
    fun objectArray(
        description: String,
        itemRequired: List<String>,
        itemProperties: Map<String, JsonNode>,
    ): ObjectNode =
        typed(type = "array", description = description)
            .also { it.set<JsonNode>("items", objectNode(required = itemRequired, properties = itemProperties)) }

    /** The complete input schema of a tool: an object with exactly the given [properties], no additional one. */
    fun schema(
        required: List<String>,
        properties: Map<String, JsonNode>,
    ): String {
        val root = objectNode(required = required, properties = properties)
        val ordered = mapper.createObjectNode().put("\$schema", "https://json-schema.org/draft/2020-12/schema")
        ordered.setAll<ObjectNode>(root)
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ordered)
    }

    private fun objectNode(
        required: List<String>,
        properties: Map<String, JsonNode>,
    ): ObjectNode {
        val node = mapper.createObjectNode().put("type", "object")
        val props = node.putObject("properties")
        properties.forEach { (name, schema) -> props.set<JsonNode>(name, schema) }
        if (required.isNotEmpty()) node.putArray("required").also { array -> required.forEach { array.add(it) } }
        node.put("additionalProperties", false)
        return node
    }

    private fun typed(
        type: String,
        description: String,
    ): ObjectNode = mapper.createObjectNode().put("type", type).put("description", description)
}
