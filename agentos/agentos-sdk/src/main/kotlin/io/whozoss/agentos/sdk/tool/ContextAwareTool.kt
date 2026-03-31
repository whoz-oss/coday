package io.whozoss.agentos.sdk.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Extended tool interface for tools that require runtime execution context.
 *
 * ContextAwareTool extends StandardTool to provide access to:
 * - Namespace and case IDs
 * - File system roots for file operations
 * - Additional runtime properties (e.g., readOnly mode)
 *
 * This is an opt-in extension: tools that don't need context continue using StandardTool.
 *
 * ## Fallback Behavior
 *
 * When a ContextAwareTool is invoked via the base `execute(input)` method (without context),
 * it delegates to `executeWithContext(input, ToolExecutionContext.empty())`. Tools MUST
 * validate that required context is present (e.g., `fileRoots.containsKey("project")`) and
 * return an explicit error message if not.
 *
 * ## Classloader Safety
 *
 * The `executeWithJsonAndContext()` method follows the same classloader-safe pattern as
 * `StandardTool.executeWithJson()`: deserialization happens within the plugin classloader
 * using the tool's `paramType`, then delegates to `executeWithContext()`.
 *
 * @param T The typed input parameter class for this tool
 */
interface ContextAwareTool<T> : StandardTool<T> {
    /**
     * Execute the tool with typed input and full runtime context.
     *
     * This is the main execution method for context-aware tools. Implementations should:
     * 1. Validate required context is present (e.g., fileRoots for file tools)
     * 2. Perform the tool operation using context (namespace ID, file roots, etc.)
     * 3. Return a human-readable result string (never throw exceptions to LLM)
     *
     * @param input Typed tool input (may be null if tool has no parameters)
     * @param context Runtime execution context (namespace, case, fileRoots, properties)
     * @return Result string to be sent to the LLM
     */
    fun executeWithContext(
        input: T?,
        context: ToolExecutionContext,
    ): String

    /**
     * Deserialize JSON and execute the tool with context.
     *
     * This method is called by the agent runtime when the tool is invoked with context.
     * It follows the same classloader-safe deserialization pattern as StandardTool.executeWithJson().
     *
     * @param json Raw JSON string from the LLM
     * @param context Runtime execution context
     * @return Result string to be sent to the LLM
     */
    fun executeWithJsonAndContext(
        json: String?,
        context: ToolExecutionContext,
    ): String {
        val type = paramType
        val input: T? =
            if (type == null || json.isNullOrBlank()) {
                null
            } else {
                objectMapper.readValue(json, type)
            }
        return executeWithContext(input, context)
    }

    /**
     * Default fallback implementation: delegates to executeWithContext with empty context.
     *
     * When a ContextAwareTool is called via the base execute() method (by a caller not aware
     * of context), this provides graceful degradation. Tools MUST validate that required context
     * is present and return an error message like:
     *
     *   "File tools require a configured namespace with project root"
     *
     * Do NOT throw exceptions, as the LLM needs a string response.
     */
    override fun execute(input: T?): String = executeWithContext(input, ToolExecutionContext.empty())

    companion object {
        private val objectMapper = jacksonObjectMapper()
    }
}
