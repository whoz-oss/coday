package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode

/** [namespaceId] is a string (`"current"`, the namespace id) or `{"$op": "<ref>"}` for a namespace created by the same plan. */
data class ScopeInput(
    val namespaceId: JsonNode? = null,
    val userId: String? = null,
)
