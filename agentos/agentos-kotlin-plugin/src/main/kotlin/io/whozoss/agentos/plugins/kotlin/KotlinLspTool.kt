package io.whozoss.agentos.plugins.kotlin

import io.whozoss.agentos.sdk.tool.BlockingTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.sdk.tool.prefixedToolName

/** Every input carries the timeout the agent chose for the call. */
internal interface KotlinToolInput {
    val timeoutSeconds: Int?
}

/**
 * One tool of the KOTLIN integration.
 *
 * The 18 tools differ by their input and by the question they ask the server, nothing else: each one is this class
 * with its own [answer], built in the `*Tools.kt` files. [answer] is blocking business code returning a
 * [KotlinToolResult]: it validates the input, then asks the server through [KotlinLspRun], which bounds the call by
 * the `timeoutSeconds` of the agent. Running it interruptibly, so that cancelling the case releases the caller and
 * the request, is the job of [BlockingTool].
 *
 * @param answer the business logic, called only when `timeoutSeconds` is present, with its value
 */
internal class KotlinLspTool<T : KotlinToolInput>(
    configName: String?,
    toolName: String,
    override val description: String,
    override val inputSchema: String,
    override val paramType: Class<T>,
    private val answer: (input: T, timeoutSeconds: Int) -> KotlinToolResult,
) : BlockingTool<T>() {
    override val name: String = prefixedToolName(configName = configName, toolName = toolName)

    override val version: String = "1.0.0"

    override fun executeBlocking(
        input: T?,
        context: ToolContext,
    ): ToolExecutionResult =
        input
            ?.let { present -> present.timeoutSeconds?.let { timeoutSeconds -> answer(present, timeoutSeconds).toToolExecutionResult() } }
            ?: missingTimeoutResult()
}
