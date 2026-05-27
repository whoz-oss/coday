package io.whozoss.agentos.sdk.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool.Companion.objectMapper

interface StandardTool<T> {
    val name: String
    val description: String
    val inputSchema: String
    val version: String
    val paramType: Class<T>?

    /**
     * Execute the tool with a typed input and return a [ToolExecutionResult].
     *
     * The default implementation wraps the returned string in a [ToolExecutionResult.success]
     * with no metadata. Override to attach metadata or signal errors via [ToolExecutionResult.error].
     */
    suspend fun execute(
        input: T?,
        context: ToolContext,
    ): ToolExecutionResult

    /**
     * Deserialize raw JSON produced by the LLM and execute the tool.
     *
     * Called from the service layer (app classloader) with the raw JSON string that
     * Spring AI extracted from the LLM response. Deserialization is performed here,
     * inside the plugin classloader, so [paramType] is always resolvable without
     * crossing classloader boundaries.
     *
     * Override this method when you need full control over deserialization.
     * The default implementation delegates to [execute] after deserializing [json]
     * with the shared [objectMapper].
     *
     * @param json Raw JSON string from the LLM (e.g. `{"timezone":"UTC"}`)
     * @param context The execution context for this tool call
     * @return The execution result including output and optional metadata
     */
    suspend fun executeWithJson(
        json: String?,
        context: ToolContext,
    ): ToolExecutionResult {
        val type = paramType
        val input: T? =
            if (type == null || json.isNullOrBlank()) {
                null
            } else {
                objectMapper.readValue(json, type)
            }
        return execute(input, context)
    }

    // ─── User-confirmation opt-in (WZ-31596) ───────────────────────────────────────────────
    //
    // A tool opts in to the confirmation flow by setting [confirmationMode] to a value
    // other than [ConfirmationMode.NONE]. The orchestrator then routes via the
    // [PendingConfirmationEvent] persistence cycle and calls [executeWithJson]
    // (post-confirmation) or [onRejected] (post-refusal).
    //
    // When [confirmationMode] is [ConfirmationMode.NONE] (the default), the tool runs
    // through its standard [execute] path — same as a tool that never opted in.

    /**
     * Declares how this tool participates in the user-confirmation flow.
     *
     * - [ConfirmationMode.NONE]: no confirmation required — tool executes directly (default).
     * - [ConfirmationMode.AT_LEAST_ONCE]: confirmation required, but the orchestrator may
     *   skip the explicit prompt when it detects implicit consent in the conversation
     *   history (via [ConfirmationManager.shouldConfirm]).
     * - [ConfirmationMode.EVERY_TIME]: confirmation required on every call; implicit consent
     *   is never trusted. Use this for irreversible side-effects (e.g. file deletion).
     */
    val confirmationMode: ConfirmationMode get() = ConfirmationMode.NONE

    /**
     * Instructions appended to the `analyzeConfirmation` prompt to guide the LLM judge
     * when interpreting the user's free-form reply. For destructive actions this SHOULD
     * be a strict prompt (e.g. "Require explicit confirmation: a bare 'ok' is not enough
     * unless the previous turn clearly described the destructive action."). Plugin authors
     * MUST NOT include user-controlled strings here.
     */
    fun getConfirmationInstructions(): String = ""

    /**
     * Called when the user rejected the action. Default returns a generic cancellation
     * message; override if the tool wants to log/clean up or produce a custom message.
     */
    fun onRejected(): String = "Action cancelled."

    companion object {
        private val objectMapper = jacksonObjectMapper()
    }
}
