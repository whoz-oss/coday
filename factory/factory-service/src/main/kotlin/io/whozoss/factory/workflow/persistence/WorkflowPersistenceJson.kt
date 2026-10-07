package io.whozoss.factory.workflow.persistence

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Jackson helpers shared by the workflow Neo4j nodes and repository adapters.
 *
 * The workflow domain models carry JSON documents as `Map<String, Any?>` (the
 * former PostgreSQL `jsonb` columns). The graph nodes keep those documents as
 * verbatim JSON text, exactly like the other migrated Factory aggregates, and
 * these helpers are the single place that (de)serialises them.
 */
internal fun ObjectMapper.writeJson(value: Any?): String = writeValueAsString(value)

/** Reads a JSON object, mapping an absent/blank document to an empty map. */
@Suppress("UNCHECKED_CAST")
internal fun ObjectMapper.readJsonMap(json: String?): Map<String, Any?> =
    if (json.isNullOrBlank()) emptyMap() else readValue(json, Map::class.java) as Map<String, Any?>

/** Reads an optional JSON object; a JSON `null` document maps to Kotlin `null`. */
@Suppress("UNCHECKED_CAST")
internal fun ObjectMapper.readJsonMapOrNull(json: String?): Map<String, Any?>? {
    if (json.isNullOrBlank() || json == "null") return null
    return readValue(json, Map::class.java) as Map<String, Any?>?
}
