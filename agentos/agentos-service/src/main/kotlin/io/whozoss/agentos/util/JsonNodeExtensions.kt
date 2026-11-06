package io.whozoss.agentos.util

import tools.jackson.databind.JsonNode

/**
 * The text of [field] without surrounding whitespace, or null when the field is missing, null, blank or not a
 * scalar value (Jackson 3 refuses to coerce an array or object to text).
 */
fun JsonNode.trimmedTextOrNull(field: String): String? =
    get(field)
        ?.takeIf { it.isValueNode && !it.isNull }
        ?.asString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/** Returns the non-blank scalar values of a JSON array field, or an empty list when absent or not an array. */
fun JsonNode?.stringList(field: String): List<String> =
    this
        ?.get(field)
        ?.takeIf { it.isArray }
        ?.values()
        ?.filter { it.isValueNode && !it.isNull }
        ?.map { it.asString() }
        ?.filter { it.isNotBlank() }
        ?: emptyList()
