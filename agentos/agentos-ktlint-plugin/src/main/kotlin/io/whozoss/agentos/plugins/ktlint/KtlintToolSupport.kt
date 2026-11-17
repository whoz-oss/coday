package io.whozoss.agentos.plugins.ktlint

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.ToolExecutionResult

/** Error type of a failed ktlint tool call. */
internal const val KTLINT_ERROR = "KTLINT_ERROR"

/**
 * Maps the String answer of a ktlint tool business method to a [ToolExecutionResult]:
 * `ERROR...` -> [KTLINT_ERROR], anything else is a success (a report listing violations is a success).
 */
internal fun String.toToolExecutionResult(): ToolExecutionResult =
    when {
        startsWith("ERROR") -> ToolExecutionResult.error(output = this, errorType = KTLINT_ERROR)
        else -> ToolExecutionResult.success(output = this)
    }

/** The agent did not provide the mandatory `timeoutSeconds`: nothing is run, the answer names the parameter. */
internal fun missingTimeoutResult(): ToolExecutionResult = "ERROR: ${ToolTimeout.MISSING_MESSAGE}".toToolExecutionResult()

/** Tool name, prefixed with `<configName>__` when the integration config has a name (same rule as the other plugins). */
internal fun prefixedToolName(
    configName: String?,
    toolName: String,
): String = if (configName != null) "${configName}__$toolName" else toolName

/** Builds the hand-written JSON Schemas (draft 2020-12) of the tool inputs with Jackson, so descriptions are escaped safely. */
internal object ToolSchema {
    private val mapper = jacksonObjectMapper()

    /** A `string` property. */
    fun string(description: String): ObjectNode = typed(type = "string", description = description)

    /** An `integer` property. */
    fun integer(description: String): ObjectNode = typed(type = "integer", description = description)

    /** An `array` property of strings. */
    fun stringArray(description: String): ObjectNode =
        typed(type = "array", description = description).also { it.putObject("items").put("type", "string") }

    /** The complete input schema of a tool. */
    fun schema(
        required: List<String>,
        properties: Map<String, JsonNode>,
    ): String {
        val root = objectNode(required = required, properties = properties)
        val ordered = mapper.createObjectNode().put("\$schema", "https://json-schema.org/draft/2020-12/schema")
        ordered.setAll<ObjectNode>(root)
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ordered)
    }

    private fun objectNode(
        required: List<String>,
        properties: Map<String, JsonNode>,
    ): ObjectNode {
        val node = mapper.createObjectNode().put("type", "object")
        val props = node.putObject("properties")
        properties.forEach { (name, schema) -> props.set<JsonNode>(name, schema) }
        if (required.isNotEmpty()) node.putArray("required").also { array -> required.forEach { array.add(it) } }
        node.put("additionalProperties", false)
        return node
    }

    private fun typed(
        type: String,
        description: String,
    ): ObjectNode = mapper.createObjectNode().put("type", type).put("description", description)
}
