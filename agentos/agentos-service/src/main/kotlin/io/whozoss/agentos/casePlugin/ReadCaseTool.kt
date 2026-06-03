package io.whozoss.agentos.casePlugin

import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import java.util.UUID

/**
 * Tool that reads the full event transcript of another [Case] in the same namespace.
 *
 * The LLM passes a [caseId] string (UUID format). The tool delegates to [caseEventsLoader]
 * which enforces namespace isolation — it returns null when the case does not exist or
 * belongs to a different namespace.
 *
 * The transcript is formatted by [CaseTranscriptFormatter] according to the
 * [includesTechnicalEvents] flag configured at plugin instantiation time.
 */
class ReadCaseTool(
    private val configName: String?,
    private val includesTechnicalEvents: Boolean,
    private val caseEventsLoader: (caseId: UUID, namespaceId: UUID) -> List<CaseEvent>?,
) : StandardTool<ReadCaseTool.Input> {

    data class Input(val caseId: String)

    override val name: String = buildToolName(configName)
    override val description: String =
        "Read the full conversation transcript of another case in the same namespace. " +
            "Useful for analysing what happened in a previous agent run."
    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java
    override val inputSchema: String = INPUT_SCHEMA

    override suspend fun execute(input: Input?, context: ToolContext): ToolExecutionResult {
        if (input == null) {
            return ToolExecutionResult.error(
                output = "Missing required parameter: caseId",
                errorType = "INVALID_INPUT",
            )
        }

        val targetCaseId = try {
            UUID.fromString(input.caseId)
        } catch (e: IllegalArgumentException) {
            return ToolExecutionResult.error(
                output = "Invalid caseId '${input.caseId}': must be a valid UUID",
                errorType = "INVALID_INPUT",
            )
        }

        val events = caseEventsLoader(targetCaseId, context.namespaceId)
            ?: return ToolExecutionResult.error(
                output = "Case '${input.caseId}' not found or is not accessible in this namespace",
                errorType = "NOT_FOUND",
            )

        if (events.isEmpty()) {
            return ToolExecutionResult.success("Case has no events")
        }

        val transcript = CaseTranscriptFormatter.format(events, includesTechnicalEvents)
        return ToolExecutionResult.success(transcript)
    }

    companion object {
        private const val INTEGRATION_TYPE = "CASE"

        fun buildToolName(configName: String?): String =
            if (configName != null) "${configName}__ReadCase" else "ReadCase"

        val INPUT_SCHEMA: String =
            """
            {
                "type": "object",
                "properties": {
                    "caseId": {
                        "type": "string",
                        "description": "UUID of the case to read, within the same namespace as the current case."
                    }
                },
                "required": ["caseId"],
                "additionalProperties": false
            }
            """.trimIndent()
    }
}
