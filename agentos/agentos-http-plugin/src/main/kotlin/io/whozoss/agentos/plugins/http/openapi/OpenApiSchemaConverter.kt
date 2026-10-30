package io.whozoss.agentos.plugins.http.openapi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Converts a ref-inlined OpenAPI schema object into plain JSON Schema for an LLM tool.
 *
 * Applied recursively:
 * - `readOnly: true` properties are removed (and from `required`);
 * - `nullable: true` becomes `"type": [T, "null"]`;
 * - `allOf` whose members are all object schemas is merged (union of `properties` and `required`);
 * - `oneOf` / `anyOf` are kept as-is;
 * - OpenAPI-only keywords ([OAS_ONLY_KEYWORDS]) are dropped.
 *
 * The input node is never mutated.
 */
object OpenApiSchemaConverter {

    fun convert(schema: JsonNode): JsonNode =
        when {
            schema.isObject -> convertObject(schema as ObjectNode)
            schema.isArray -> JsonNodeFactory.instance.arrayNode().apply { schema.forEach { add(convert(it)) } }
            else -> schema
        }

    private fun convertObject(schema: ObjectNode): ObjectNode {
        val merged = mergeAllOf(schema)
        val result = JsonNodeFactory.instance.objectNode()
        merged.properties()
            .filter { (key, _) -> key !in OAS_ONLY_KEYWORDS && key != NULLABLE }
            .forEach { (key, value) -> result.set<JsonNode>(key, convertMember(key, value)) }
        removeReadOnlyProperties(result, merged.path("properties"))
        if (merged.path(NULLABLE).asBoolean(false)) applyNullable(result)
        return result
    }

    private fun convertMember(key: String, value: JsonNode): JsonNode =
        when {
            key == "properties" && value.isObject -> convertProperties(value as ObjectNode)
            key in RAW_KEYWORDS -> value.deepCopy()
            else -> convert(value)
        }

    private fun convertProperties(properties: ObjectNode): ObjectNode {
        val result = JsonNodeFactory.instance.objectNode()
        properties.properties().forEach { (name, property) -> result.set<JsonNode>(name, convert(property)) }
        return result
    }

    private fun removeReadOnlyProperties(result: ObjectNode, sourceProperties: JsonNode) {
        val readOnly = sourceProperties.properties()
            .filter { (_, property) -> property.path("readOnly").asBoolean(false) }
            .map { it.key }
            .toSet()
        if (readOnly.isEmpty()) return
        (result.get("properties") as? ObjectNode)?.remove(readOnly)
        (result.get("required") as? ArrayNode)?.let { required ->
            val kept = required.filter { it.asText() !in readOnly }
            required.removeAll()
            required.addAll(kept)
        }
    }

    private fun applyNullable(result: ObjectNode) {
        val type = result.get("type") ?: return
        val types = if (type.isArray) type.map { it.asText() } else listOf(type.asText())
        val withNull = if (NULL in types) types else types + NULL
        result.set<JsonNode>("type", JsonNodeFactory.instance.arrayNode().apply { withNull.forEach { add(it) } })
    }

    /**
     * Returns [schema] itself, or a merged copy when it is an `allOf` of object schemas.
     * Members that are themselves `allOf` compositions are merged first, so nested chains contribute their
     * properties and required names too.
     */
    private fun mergeAllOf(schema: ObjectNode): ObjectNode {
        val members = schema.get("allOf")
            ?.takeIf { it.isArray && it.size() > 0 && it.all(::isObjectSchema) }
            ?: return schema
        val merged = schema.deepCopy()
        merged.remove("allOf")
        merged.put("type", "object")
        val properties = merged.withObject("properties")
        val required = linkedSetOf<String>()
        members.map { mergeAllOf(it as ObjectNode) }.forEach { member ->
            member.path("properties").properties().forEach { (name, property) ->
                properties.set<JsonNode>(name, property)
            }
            member.path("required").forEach { required.add(it.asText()) }
        }
        merged.path("required").forEach { required.add(it.asText()) }
        if (required.isNotEmpty()) {
            val requiredNode = JsonNodeFactory.instance.arrayNode().apply { required.forEach { add(it) } }
            merged.set<JsonNode>("required", requiredNode)
        }
        return merged
    }

    private fun isObjectSchema(node: JsonNode): Boolean =
        node.isObject && (node.path("type").asText() == "object" || node.has("properties") || node.has("allOf"))

    private const val NULLABLE = "nullable"
    private const val NULL = "null"

    /** Keywords whose values are data, not schemas: copied verbatim. */
    private val RAW_KEYWORDS = setOf("enum", "default", "const", "required", "type", "description", "title")

    val OAS_ONLY_KEYWORDS: Set<String> =
        setOf("xml", "example", "examples", "externalDocs", "discriminator", "deprecated", "readOnly", "writeOnly")
}
