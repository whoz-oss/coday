package io.whozoss.agentos.selfConfig

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.util.UUID

/**
 * Shared rendering for the `SELF_CONFIG` tools: one definition of how a personal
 * [IntegrationConfig] is shown to the LLM, so the row an agent reads back after a write is
 * described exactly as the one it listed a moment earlier.
 *
 * `parameters` are rendered in clear text. That is safe here, and only here, because every row
 * these tools can reach belongs to the calling user -- see the "User-owned overlays only" section
 * of [SelfConfigToolService].
 */
internal object SelfConfigRendering {
    val mapper = jacksonObjectMapper()

    /** Scope label as the agent should reason about it, derived from the row's own scope pair. */
    fun scopeOf(config: IntegrationConfig): String = if (config.namespaceId == null) "global" else "namespace"

    fun toNode(config: IntegrationConfig): ObjectNode =
        mapper.createObjectNode().apply {
            put("id", config.metadata.id.toString())
            put("name", config.name)
            put("integrationType", config.integrationType)
            put("scope", scopeOf(config))
            put("description", config.description)
            set<JsonNode>("parameters", config.parameters ?: nullNode())
            put("authSettingName", config.authSettingName)
            put("autoGrant", config.autoGrant)
        }

    /** One-line human summary; the LLM reads this even when it ignores the structured output. */
    fun summarize(config: IntegrationConfig): String =
        "'${config.name}' (type ${config.integrationType}, ${scopeOf(config)} scope, id ${config.metadata.id})"

    /** JSON Schema of a single rendered configuration, reused as the item type of the list tool. */
    val CONFIG_SCHEMA: JsonNode =
        mapper.readTree(
            """
            {
              "type": "object",
              "properties": {
                "id":              { "type": "string", "format": "uuid" },
                "name":            { "type": "string" },
                "integrationType": { "type": "string" },
                "scope":           { "type": "string", "enum": ["namespace", "global"] },
                "description":     { "type": ["string", "null"] },
                "parameters":      { "type": ["object", "null"] },
                "authSettingName": { "type": ["string", "null"] },
                "autoGrant":       { "type": "boolean" }
              },
              "required": ["id", "name", "integrationType", "scope"]
            }
            """.trimIndent(),
        )

    val CONFIG_ARRAY_SCHEMA: JsonNode =
        mapper.createObjectNode().apply {
            put("type", "array")
            set<JsonNode>("items", CONFIG_SCHEMA)
        }

    /** Uniform translation of a [SelfConfigOutcome.Failure] into a tool error. */
    fun toError(failure: SelfConfigOutcome.Failure): ToolExecutionResult =
        ToolExecutionResult.error(failure.message, errorType = failure.errorType)

    /**
     * Parses a UUID supplied by the LLM, which is a plausible place for it to hallucinate a
     * malformed value; returning null lets the tool answer with a readable error instead of
     * throwing out of [io.whozoss.agentos.sdk.tool.StandardTool.execute].
     */
    fun parseId(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
}
