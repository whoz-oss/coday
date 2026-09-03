package io.whozoss.agentos.redirect

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult

/**
 * Tool that returns the process guideline stored in the REDIRECT [IntegrationConfig].
 *
 * The LLM calls this tool when it has finished helping the user and needs to determine
 * whether a next step exists in the current workflow. The tool returns the guideline
 * wrapped in a JSON object (`{"guideline": "..."}`) so the response is always valid JSON,
 * which prevents downstream LLMs from misinterpreting plain text as tool-call arguments.
 *
 * ## Design rationale
 *
 * Keeping the guideline out of the agent's system prompt preserves context efficiency:
 * the process guideline is long, rarely needed on every turn, and only relevant at the
 * moment the agent concludes its task. Delivering it as a tool response means the LLM
 * receives it exactly when it is about to decide on the next step.
 *
 * The tool takes no input — the LLM calls it with an empty argument object. The
 * [inputSchema] reflects this with an empty `properties` object.
 *
 * @param configName The [IntegrationConfig.name] used as tool-name prefix (e.g.
 *   `"REDIRECT_all__whatsNext"`).
 * @param guideline The process guideline string from the integration config parameters.
 */
class WhatsNextTool(
    configName: String?,
    private val guideline: String,
) : StandardTool<Nothing> {
    override val name: String = configName?.let { "${it}__whatsNext" } ?: "whatsNext"

    override val description: String =
        "Call this tool when you have finished the part of the work that falls within your scope. " +
            "It returns a guideline telling you what the workflow expects next: either that another agent should " +
            "take over (and which one), or that your step was the last one. If it names another agent, don't " +
            "attempt that agent's work and switch to it.\n\n" +
            "Call it once, at completion, including when the request turns out to be outside your scope and you've done what you can."

    override val inputSchema: String =
        """
        {
          "type": "object",
          "properties": {},
          "additionalProperties": false
        }
        """.trimIndent()

    override val version: String = "1.0.0"

    /**
     * No input type — the LLM always sends `{}`. Setting paramType to null causes
     * [executeWithJson] to skip deserialization and call [execute] with null directly.
     */
    override val paramType: Class<Nothing>? = null

    /**
     * Returns the guideline wrapped in a JSON object so the tool response is always
     * valid JSON. Plain-text responses can be mistaken by some LLMs as argument JSON
     * for subsequent tool calls, causing a parse error.
     */
    override suspend fun execute(
        input: Nothing?,
        context: ToolContext,
    ): ToolExecutionResult = ToolExecutionResult.success(objectMapper.writeValueAsString(mapOf("guideline" to guideline)))

    companion object {
        private val objectMapper = jacksonObjectMapper()
    }
}
