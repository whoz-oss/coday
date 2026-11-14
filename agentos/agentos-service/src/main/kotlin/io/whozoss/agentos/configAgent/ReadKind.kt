package io.whozoss.agentos.configAgent

import io.whozoss.agentos.sdk.api.configChange.ConfigKind

/**
 * What `AGENTOS_CONFIG__read` can read. [wireName] is the value the LLM sends; [configKind] is set
 * for the kinds that are configuration entities, listed or read by id.
 */
enum class ReadKind(
    val wireName: String,
    val configKind: ConfigKind? = null,
) {
    AGENT("agent", ConfigKind.AGENT),
    SKILL("skill", ConfigKind.SKILL),
    PROMPT("prompt", ConfigKind.PROMPT),
    INTEGRATION("integration", ConfigKind.INTEGRATION),
    AUTH_SETTING("auth-setting", ConfigKind.AUTH_SETTING),
    NAMESPACE("namespace", ConfigKind.NAMESPACE),
    AI_PROVIDER("ai-provider", ConfigKind.AI_PROVIDER),
    AI_MODEL("ai-model", ConfigKind.AI_MODEL),
    INTEGRATION_TYPE("integration-type"),
    DEPLOYMENT("deployment"),
    CAPABILITIES("capabilities"),
    MESSAGING("messaging"),
    ;

    companion object {
        fun fromWire(raw: String?): ReadKind? = raw?.trim()?.lowercase()?.let { value -> entries.firstOrNull { it.wireName == value } }

        /** The wire names as a JSON array, for the tool's input schema and its error message. */
        val wireNames: String = entries.joinToString(", ") { "\"${it.wireName}\"" }
    }
}
