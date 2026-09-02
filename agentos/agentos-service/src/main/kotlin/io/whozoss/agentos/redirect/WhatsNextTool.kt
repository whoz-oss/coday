package io.whozoss.agentos.redirect

import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult

/**
 * Tool that returns the process guideline stored in the REDIRECT [IntegrationConfig].
 *
 * The LLM calls this tool when it has finished helping the user and needs to determine
 * whether a next step exists in the current workflow. The tool returns the guideline
 * verbatim; the agent then reasons over it — using the available [RedirectTool] if a
 * handoff is warranted — without any additional LLM call inside this tool.
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
) : StandardTool<WhatsNextTool.Input> {
    /** Empty input — the LLM calls this tool with no arguments. */
    class Input

    override val name: String = configName?.let { "${it}__whatsNext" } ?: "whatsNext"

    override val description: String =
        "Call this tool when you have finished helping the user with their current request. " +
            "It returns the process guideline that tells you whether another agent should take over " +
            "and, if so, which one. Always call this tool before ending your turn."

    override val inputSchema: String =
        """
        {
          "type": "object",
          "properties": {},
          "additionalProperties": false
        }
        """.trimIndent()

    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java

    override suspend fun execute(input: Input?, context: ToolContext): ToolExecutionResult =
        ToolExecutionResult.success(guideline)
}
