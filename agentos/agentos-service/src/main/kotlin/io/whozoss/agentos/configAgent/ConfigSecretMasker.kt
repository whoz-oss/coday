package io.whozoss.agentos.configAgent

import io.whozoss.agentos.sdk.api.configChange.ConfigKind
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.StringNode

/**
 * Masks the secrets of a configuration entity before it leaves the server through the config
 * agent: tool output (stored in the case history, readable by every member of the case, and sent to
 * the LLM provider) and the card's diff.
 *
 * Integration parameters are masked closed by default: plugin schemas do not mark their secrets
 * yet, so every string leaf is masked except a short list of keys that only locate a service.
 */
internal object ConfigSecretMasker {
    fun mask(
        kind: ConfigKind,
        dto: JsonNode,
    ): JsonNode {
        val copy = dto.deepCopy()
        if (copy !is ObjectNode) return copy
        when (kind) {
            ConfigKind.INTEGRATION -> {
                copy.get("parameters")?.let { copy.set("parameters", maskParameters(it, parentKey = null)) }
            }

            ConfigKind.AUTH_SETTING -> {
                ConfigSecretPolicy.AUTH_SETTING_SECRET_FIELDS.forEach { field ->
                    if (copy.hasNonNull(field)) copy.put(field, ConfigJson.MASK)
                }
            }

            else -> {
                Unit
            }
        }
        return copy
    }

    private fun maskParameters(
        node: JsonNode,
        parentKey: String?,
    ): JsonNode =
        when (node) {
            is ObjectNode -> {
                JsonNodeFactory.instance.objectNode().also { copy ->
                    node.properties().forEach { (key, value) -> copy.set(key, maskParameters(value, key)) }
                }
            }

            is ArrayNode -> {
                JsonNodeFactory.instance.arrayNode().also { copy -> node.values().forEach { copy.add(maskParameters(it, parentKey)) } }
            }

            is StringNode -> {
                if (parentKey?.lowercase() in
                    ConfigSecretPolicy.NON_SECRET_PARAMETER_KEYS
                ) {
                    node
                } else {
                    StringNode.valueOf(ConfigJson.MASK)
                }
            }

            else -> {
                node
            }
        }
}
