package io.whozoss.agentos.util

import com.fasterxml.jackson.databind.JsonNode

/** The text of [field] without surrounding whitespace, or null when the field is missing, null or blank. */
fun JsonNode.trimmedTextOrNull(field: String): String? =
    get(field)
        ?.takeIf { !it.isNull }
        ?.asText()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
