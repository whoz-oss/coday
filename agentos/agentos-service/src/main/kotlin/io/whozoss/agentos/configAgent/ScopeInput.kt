package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode

/**
 * [namespaceId] is [CURRENT_NAMESPACE], a namespace id, or `{"$op": "<ref>"}` for a namespace created by the
 * same plan; [userId] is [CALLER] or null.
 */
data class ScopeInput(
    val namespaceId: JsonNode? = null,
    val userId: String? = null,
) {
    companion object {
        /** What the LLM writes for the namespace the agent runs in, here and in `AGENTOS_CONFIG__read`. */
        const val CURRENT_NAMESPACE = "current"

        /** What the LLM writes for the user who applies the plan. */
        const val CALLER = "me"
    }
}
