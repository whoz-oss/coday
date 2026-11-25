package io.whozoss.agentos.plugins.ktlint

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.ToolExecutionResult

/** Error type of a ktlint run that found violations still to fix. */
internal const val KTLINT_FAILURE = "KTLINT_FAILURE"

/** Error type of a ktlint tool call that could not do its job. */
internal const val KTLINT_ERROR = "KTLINT_ERROR"

/**
 * Outcome of a ktlint tool call, decided where it is known and never guessed back from the text.
 *
 * - [Success]: ktlint ran and the code is clean (or was fully autocorrected).
 * - [Failure]: ktlint ran and found violations still to fix: [text] is its report, the answer the agent needs.
 * - [Error]: the tool could not do its job (bad input, path refused, timeout, interruption): [text] says why.
 */
sealed interface KtlintToolResult {
    val text: String

    data class Success(
        override val text: String,
    ) : KtlintToolResult

    data class Failure(
        override val text: String,
    ) : KtlintToolResult

    data class Error(
        override val text: String,
    ) : KtlintToolResult

    companion object {
        /** [Success] when there is no violation left to report, [Failure] otherwise. */
        fun ofViolations(
            remaining: Int,
            text: String,
        ): KtlintToolResult = if (remaining == 0) Success(text) else Failure(text)
    }
}

/** `Success` -> success, `Failure` -> `KTLINT_FAILURE`, `Error` -> `KTLINT_ERROR` (its text is prefixed with `ERROR: `). */
internal fun KtlintToolResult.toToolExecutionResult(): ToolExecutionResult =
    when (this) {
        is KtlintToolResult.Success -> ToolExecutionResult.success(output = text)
        is KtlintToolResult.Failure -> ToolExecutionResult.error(output = text, errorType = KTLINT_FAILURE)
        is KtlintToolResult.Error -> ToolExecutionResult.error(output = "ERROR: $text", errorType = KTLINT_ERROR)
    }

/** The agent did not provide the mandatory `timeoutSeconds`: nothing is run, the answer names the parameter. */
internal fun missingTimeoutResult(): ToolExecutionResult = KtlintToolResult.Error(text = ToolTimeout.MISSING_MESSAGE).toToolExecutionResult()

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
