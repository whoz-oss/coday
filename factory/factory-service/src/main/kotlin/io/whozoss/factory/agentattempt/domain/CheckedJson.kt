package io.whozoss.factory.agentattempt.domain

/** Copies a JSON object without coercing keys or silently discarding malformed entries. */
internal fun Map<*, *>.toCheckedJsonObject(): Map<String, Any?> = entries.associate { (key, value) ->
    require(key is String) { "JSON object keys must be strings" }
    key to value
}

/** Absent or null legacy fields stay absent; any non-null value must be an object. */
internal fun Any?.checkedJsonObjectOrNull(): Map<String, Any?>? {
    if (this == null) return null
    require(this is Map<*, *>) { "Expected a JSON object" }
    return toCheckedJsonObject()
}
