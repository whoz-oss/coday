package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode

/**
 * One field of an `UPDATE`. [beforeHash] is the hash of the masked value the server read at
 * `propose` time (null for a secret path), so a value changed since then is reported as a conflict
 * instead of being overwritten.
 */
data class PlannedChange(
    val path: String,
    val after: JsonNode? = null,
    val remove: Boolean = false,
    val beforeHash: String? = null,
)
