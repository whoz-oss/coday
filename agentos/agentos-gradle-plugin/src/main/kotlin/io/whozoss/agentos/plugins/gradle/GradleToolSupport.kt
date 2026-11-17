package io.whozoss.agentos.plugins.gradle

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Maps the String answer of a Gradle tool business method to a [ToolExecutionResult]:
 * `ERROR...` -> `GRADLE_ERROR`, `FAILURE...` -> `GRADLE_FAILURE`, anything else is a success.
 */
internal fun String.toToolExecutionResult(): ToolExecutionResult =
    when {
        startsWith("ERROR") -> ToolExecutionResult.error(this, errorType = "GRADLE_ERROR")
        startsWith("FAILURE") -> ToolExecutionResult.error(this, errorType = "GRADLE_FAILURE")
        else -> ToolExecutionResult.success(this)
    }

/** The agent did not provide the mandatory `timeoutSeconds`: nothing is run, the answer names the parameter. */
internal fun missingTimeoutResult(): ToolExecutionResult = "ERROR: ${ToolTimeout.MISSING_MESSAGE}".toToolExecutionResult()

/**
 * Runs the blocking body of a Gradle tool on the IO dispatcher, interruptibly, and maps its answer with
 * [toToolExecutionResult].
 *
 * The bodies wait on the Gradle process for up to [ToolTimeout.MAX_SECONDS] seconds. Called straight from the
 * suspending `execute`, they would hold a dispatcher thread for that whole time and never see the cancellation of
 * the run: stopping the case would leave Gradle running to its end. With [runInterruptible] the cancellation
 * interrupts the waiting thread, [ProcessLauncher] kills the whole process tree, and the cancellation reaches the
 * caller, whatever the body answers once interrupted.
 */
internal suspend fun runInterruptibleTool(body: () -> String): ToolExecutionResult =
    runInterruptible(Dispatchers.IO, body).toToolExecutionResult()

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
