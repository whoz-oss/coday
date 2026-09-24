package io.whozoss.agentos.plugins.http.openapi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Builds the JSON Schema of a tool's input from an [OpenApiOperation].
 *
 * Root: `{"type":"object","properties":{...},"required":[...],"additionalProperties":false}`, no `$schema` key.
 * Path, query and header parameters ([EXPOSED_LOCATIONS]) become one property each (path and explicitly
 * required parameters are required; a header property is described as `HTTP header <Name>`); a request body
 * becomes one property named [BODY_PROPERTY], required when the body is. Cookie parameters are not exposed,
 * and reserved header parameters are removed beforehand by [OperationCurator]. [BODY_PROPERTY] is reserved:
 * an operation declaring both a request body and a parameter of that name is a precondition failure
 * ([OperationCurator] drops it first).
 *
 * When the serialised schema exceeds [MAX_SCHEMA_BYTES], object schemas nested deeper than
 * [MAX_NESTING_LEVEL] (inside properties as well as `oneOf`/`anyOf`/`allOf` members) are replaced by
 * `{"type":"object","description":"<title or property name>"}` and the result is flagged [Result.reduced];
 * the threshold is lowered level by level while the result still exceeds the limit.
 */
object JsonSchemaBuilder {

    /** @property reduced True when deep object schemas were collapsed to fit the size limit. */
    data class Result(val json: String, val reduced: Boolean)

    private val mapper = ObjectMapper()

    fun build(operation: OpenApiOperation): Result {
        val exposed = operation.parameters.filter { it.location in EXPOSED_LOCATIONS }
        require(operation.requestBody == null || exposed.none { it.name == BODY_PROPERTY }) {
            "Operation ${operation.key} declares a parameter named '$BODY_PROPERTY', reserved for the request body"
        }
        val root = JsonNodeFactory.instance.objectNode().put("type", "object")
        val properties = root.putObject("properties")
        val required = root.putArray("required")
        exposed.forEach { parameter ->
            properties.set<JsonNode>(parameter.name, parameterProperty(parameter))
            if (parameter.required) required.add(parameter.name)
        }
        operation.requestBody?.let { body ->
            properties.set<JsonNode>(BODY_PROPERTY, OpenApiSchemaConverter.convert(body.schema))
            if (body.required) required.add(BODY_PROPERTY)
        }
        root.put("additionalProperties", false)
        return serialise(root)
    }

    private fun parameterProperty(parameter: OpenApiParameter): ObjectNode {
        val converted = OpenApiSchemaConverter.convert(parameter.schema)
        val property = JsonNodeFactory.instance.objectNode()
        PARAMETER_KEYWORDS.forEach { key -> converted.get(key)?.let { property.set<JsonNode>(key, it) } }
        describe(parameter)?.let { property.put("description", it) }
        return property
    }

    private fun describe(parameter: OpenApiParameter): String? =
        if (parameter.location == ParameterLocation.HEADER) {
            listOfNotNull("HTTP header ${parameter.name}", parameter.description).joinToString(". ")
        } else {
            parameter.description
        }

    private fun serialise(root: ObjectNode): Result {
        val json = mapper.writeValueAsString(root)
        if (fits(json)) return Result(json = json, reduced = false)
        return Result(json = reduceToFit(root, maxLevel = MAX_NESTING_LEVEL), reduced = true)
    }

    /**
     * Serialises [root] with object schemas beyond [maxLevel] collapsed, lowering the threshold one level at a
     * time until the result fits; the [MIN_NESTING_LEVEL] candidate is returned even when it still does not.
     */
    private fun reduceToFit(root: ObjectNode, maxLevel: Int): String {
        val json = mapper.writeValueAsString(reduce(root, level = 0, name = null, maxLevel = maxLevel))
        return if (fits(json) || maxLevel <= MIN_NESTING_LEVEL) json else reduceToFit(root, maxLevel - 1)
    }

    private fun fits(json: String): Boolean = json.toByteArray(Charsets.UTF_8).size <= MAX_SCHEMA_BYTES

    /**
     * Copies [node], collapsing object schemas nested deeper than [maxLevel]; the members of a composition
     * array (`oneOf`, `anyOf`, `allOf`) sit at the same level as their parent schema.
     */
    private fun reduce(node: JsonNode, level: Int, name: String?, maxLevel: Int): JsonNode =
        when {
            node.isArray -> reduceArray(node, level, name, maxLevel)
            !node.isObject -> node
            level > maxLevel && isObjectSchema(node) -> placeholder(node, name)
            else -> reduceObject(node, level, name, maxLevel)
        }

    private fun reduceArray(node: JsonNode, level: Int, name: String?, maxLevel: Int): ArrayNode {
        val copy = JsonNodeFactory.instance.arrayNode()
        node.forEach { copy.add(reduce(it, level, name, maxLevel)) }
        return copy
    }

    private fun reduceObject(node: JsonNode, level: Int, name: String?, maxLevel: Int): ObjectNode {
        val copy = JsonNodeFactory.instance.objectNode()
        node.properties().forEach { (key, value) ->
            val reduced = when (key) {
                "properties" -> reduceProperties(value, level, maxLevel)
                else -> reduce(value, level, name, maxLevel)
            }
            copy.set<JsonNode>(key, reduced)
        }
        return copy
    }

    private fun reduceProperties(properties: JsonNode, level: Int, maxLevel: Int): JsonNode {
        val copy = JsonNodeFactory.instance.objectNode()
        properties.properties().forEach { (name, property) ->
            copy.set<JsonNode>(name, reduce(property, level + 1, name, maxLevel))
        }
        return copy
    }

    private fun isObjectSchema(node: JsonNode): Boolean =
        node.has("properties") || node.path("type").asText() == "object"

    private fun placeholder(node: JsonNode, name: String?): ObjectNode =
        JsonNodeFactory.instance.objectNode()
            .put("type", "object")
            .put("description", node.path("title").takeIf { it.isTextual }?.asText() ?: name ?: "object")

    const val BODY_PROPERTY = "body"

    /** Parameter locations that become input-schema properties. */
    val EXPOSED_LOCATIONS: Set<ParameterLocation> =
        setOf(ParameterLocation.PATH, ParameterLocation.QUERY, ParameterLocation.HEADER)
    const val MAX_SCHEMA_BYTES = 32 * 1024
    const val MAX_NESTING_LEVEL = 3

    /** Lowest threshold tried: level 1 is the body itself, so its direct object properties collapse. */
    private const val MIN_NESTING_LEVEL = 1

    /** Keywords copied from a parameter schema into its property. */
    private val PARAMETER_KEYWORDS =
        listOf("description", "type", "format", "enum", "default", "minimum", "maximum", "items")
}
