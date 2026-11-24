package io.whozoss.agentos.plugins.gradle

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.ToolExecutionResult

/**
 * Outcome of a Gradle tool call, decided where it is known and never guessed back from the text.
 *
 * - [Success]: Gradle ran and succeeded.
 * - [Failure]: Gradle ran and failed (build, compilation or tests): [text] is its report, the answer the agent needs.
 * - [Error]: the tool could not do its job (bad input, no `gradlew`, timeout, interruption): [text] says why.
 */
sealed interface GradleToolResult {
    val text: String

    data class Success(
        override val text: String,
    ) : GradleToolResult

    data class Failure(
        override val text: String,
    ) : GradleToolResult

    data class Error(
        override val text: String,
    ) : GradleToolResult

    companion object {
        /** [Success] when Gradle exited with 0, [Failure] otherwise. */
        fun ofExit(
            exitCode: Int,
            text: String,
        ): GradleToolResult = if (exitCode == 0) Success(text) else Failure(text)
    }
}

/** `Success` -> success, `Failure` -> `GRADLE_FAILURE`, `Error` -> `GRADLE_ERROR` (its text is prefixed with `ERROR: `). */
internal fun GradleToolResult.toToolExecutionResult(): ToolExecutionResult =
    when (this) {
        is GradleToolResult.Success -> ToolExecutionResult.success(text)
        is GradleToolResult.Failure -> ToolExecutionResult.error(text, errorType = "GRADLE_FAILURE")
        is GradleToolResult.Error -> ToolExecutionResult.error("ERROR: $text", errorType = "GRADLE_ERROR")
    }

/** The agent did not provide the mandatory `timeoutSeconds`: nothing is run, the answer names the parameter. */
internal fun missingTimeoutResult(): ToolExecutionResult = GradleToolResult.Error(ToolTimeout.MISSING_MESSAGE).toToolExecutionResult()

/** Tool name, prefixed with `<configName>__` when the integration config has a name (same rule as the other plugins). */
internal fun prefixedToolName(
    configName: String?,
    toolName: String,
): String = if (configName != null) "${configName}__$toolName" else toolName

/** Builds the hand-written JSON Schemas (draft 2020-12) of the tool inputs with Jackson, so descriptions are escaped safely. */
internal object ToolSchema {
    private val mapper = jacksonObjectMapper()

    fun string(description: String): ObjectNode = typed("string", description)

    fun integer(description: String): ObjectNode = typed("integer", description)

    fun stringArray(description: String): ObjectNode =
        typed("array", description).also { it.putObject("items").put("type", "string") }

    fun objectArray(
        description: String,
        itemRequired: List<String>,
        itemProperties: Map<String, JsonNode>,
    ): ObjectNode =
        typed("array", description).also { it.set<JsonNode>("items", objectNode(itemRequired, itemProperties)) }

    /** The complete input schema of a tool. */
    fun schema(
        required: List<String>,
        properties: Map<String, JsonNode>,
    ): String {
        val root = objectNode(required, properties)
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
