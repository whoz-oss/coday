package io.whozoss.agentos.sdk.tool

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.sdk.caseEvent.MessageContent

/**
 * Result of a [StandardTool] execution.
 *
 * Carries the textual output the LLM will see, optional opaque metadata for
 * the service layer, and observability fields (success flag, error type, error message).
 *
 * [metadata] is intentionally untyped ([Map]<[String], [Any?]>) — each integration
 * decides what to store there. Typical use cases: entity references for coherence
 * checks on subsequent tool calls, pagination cursors, session tokens.
 * The service layer persists [metadata] alongside the [io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent]
 * so that later tool invocations in the same case can read it back via
 * [ToolContext.caseEvents].
 *
 * [images] carries visual attachments produced by the tool (e.g. an image file read for
 * vision, a PDF rendered page by page). Provider tool responses are text-only, so the
 * service layer delivers [images] to the LLM as a follow-up user message at prompt-build
 * time. When returning images, [output] MUST still contain a short textual summary of what
 * the images are: it is what transcripts, non-vision paths and the tool-response wire
 * message show. [MessageContent.Image] is an SDK class, hence classloader-safe across the
 * PF4J plugin boundary.
 *
 * [structuredOutput] carries machine-readable output that conforms to the JSON Schema
 * declared by [StandardTool.outputSchema]. It is optional — tools that return only a
 * textual result leave it null. When present, the service layer persists it alongside
 * the [output] text so downstream consumers (other tools, API clients) can process it
 * without re-parsing free-form text. [output] MUST still contain a human-readable
 * summary even when [structuredOutput] is set.
 *
 * Use the companion factory methods for the common cases:
 * ```kotlin
 * // happy path, no metadata
 * return ToolExecutionResult.success("result text")
 *
 * // happy path, with metadata
 * return ToolExecutionResult.success("result text", mapOf("entityId" to id))
 *
 * // happy path, with images
 * return ToolExecutionResult.successWithImages("Rendered cv.pdf: 3 pages", images)
 *
 * // error
 * return ToolExecutionResult.error("Something went wrong", errorType = "NOT_FOUND")
 * ```
 */
data class ToolExecutionResult(
    val output: String,
    val success: Boolean,
    val metadata: Map<String, Any?> = emptyMap(),
    val errorType: String? = null,
    val errorMessage: String? = null,
    val images: List<MessageContent.Image> = emptyList(),
    val structuredOutput: JsonNode? = null,
) {
    companion object {
        fun success(
            output: String,
            metadata: Map<String, Any?> = emptyMap(),
            structuredOutput: JsonNode? = null,
        ): ToolExecutionResult =
            ToolExecutionResult(
                output = output,
                success = true,
                metadata = metadata,
                structuredOutput = structuredOutput,
            )

        fun successWithImages(
            output: String,
            images: List<MessageContent.Image>,
            metadata: Map<String, Any?> = emptyMap(),
            structuredOutput: JsonNode? = null,
        ): ToolExecutionResult =
            ToolExecutionResult(
                output = output,
                success = true,
                metadata = metadata,
                images = images,
                structuredOutput = structuredOutput,
            )

        fun error(
            output: String,
            errorType: String? = null,
            errorMessage: String? = null,
        ): ToolExecutionResult =
            ToolExecutionResult(
                output = output,
                success = false,
                errorType = errorType,
                errorMessage = errorMessage,
            )
    }
}
