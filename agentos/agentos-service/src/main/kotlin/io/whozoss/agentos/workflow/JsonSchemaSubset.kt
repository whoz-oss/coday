package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.JsonNode

/**
 * Checks a JSON value against the subset of JSON Schema that tool `inputSchema`s use in practice.
 * There is no JSON Schema library in the service; this covers what a scheduled prompt's
 * `workflowInput` needs to be refused at save rather than fail at run time (handover D8).
 *
 * Supported keywords, applied recursively to `properties` and array `items`:
 * - `type` (a name or a list of names: object, array, string, number, integer, boolean, null)
 * - `required`
 * - `properties`
 * - `additionalProperties: false` (any other value, or absence, allows extra keys)
 * - `enum`
 *
 * Every other keyword (`format`, `minimum`, `pattern`, `oneOf`, `$ref`…) is **ignored, not
 * rejected**: such a constraint is left to the tool at run time.
 */
object JsonSchemaSubset {
    /**
     * The problems found in [value] against [schema], each prefixed by its JSON path (`$.a.b[0]`).
     * Empty when [value] conforms. Keys named in [ignoredRequired] are not required at the top level
     * (they are supplied by someone else, e.g. the workflow wiring).
     */
    fun problems(
        value: JsonNode,
        schema: JsonNode,
        ignoredRequired: Set<String> = emptySet(),
    ): List<String> = buildList { check(value, schema, "$", ignoredRequired, this) }

    private fun check(
        value: JsonNode,
        schema: JsonNode,
        path: String,
        ignoredRequired: Set<String>,
        problems: MutableList<String>,
    ) {
        val types = schema.get("type")?.let { node -> if (node.isArray) node.map { it.asText() } else listOf(node.asText()) }
        if (types != null && types.none { matchesType(value, it) }) {
            problems += "$path: expected ${types.joinToString(" or ")}, got ${typeOf(value)}"
            return
        }
        schema.get("enum")?.takeIf { it.isArray }?.let { allowed ->
            if (allowed.none { it == value }) problems += "$path: must be one of $allowed"
        }
        if (value.isObject) checkObject(value, schema, path, ignoredRequired, problems)
        if (value.isArray) {
            schema.get("items")?.takeIf { it.isObject }?.let { itemSchema ->
                value.forEachIndexed { index, item -> check(item, itemSchema, "$path[$index]", emptySet(), problems) }
            }
        }
    }

    private fun checkObject(
        value: JsonNode,
        schema: JsonNode,
        path: String,
        ignoredRequired: Set<String>,
        problems: MutableList<String>,
    ) {
        val properties = schema.get("properties")
        schema.get("required")?.forEach { required ->
            val name = required.asText()
            if (name !in ignoredRequired && !value.has(name)) problems += "$path: missing required '$name'"
        }
        val closed = schema.get("additionalProperties")?.let { it.isBoolean && !it.booleanValue() } ?: false
        value.fieldNames().forEach { name ->
            val propertySchema = properties?.get(name)
            when {
                propertySchema != null -> check(value[name], propertySchema, "$path.$name", emptySet(), problems)
                closed -> problems += "$path: unknown property '$name'"
            }
        }
    }

    private fun matchesType(
        value: JsonNode,
        type: String,
    ): Boolean =
        when (type) {
            "object" -> value.isObject
            "array" -> value.isArray
            "string" -> value.isTextual
            "number" -> value.isNumber
            "integer" -> value.isIntegralNumber
            "boolean" -> value.isBoolean
            "null" -> value.isNull
            else -> true // unknown type keyword: not ours to reject
        }

    private fun typeOf(value: JsonNode): String =
        when {
            value.isObject -> "object"
            value.isArray -> "array"
            value.isTextual -> "string"
            value.isIntegralNumber -> "integer"
            value.isNumber -> "number"
            value.isBoolean -> "boolean"
            value.isNull -> "null"
            else -> value.nodeType.name.lowercase()
        }
}
