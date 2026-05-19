package io.whozoss.agentos.sdk.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

interface StandardTool<T> {
    val name: String
    val description: String
    val inputSchema: String
    val version: String
    val paramType: Class<T>?

    fun execute(
        input: T?,
        context: ToolContext,
    ): String

    /**
     * Parse the raw JSON the LLM produced into the tool's typed input.
     *
     * Runs in the plugin classloader, so [paramType] is always resolvable. Exposed as a
     * separate method (rather than buried inside [executeWithJson]) so the orchestrator
     * can read the typed input — for example to evaluate [requiresConfirmation] — without
     * having to re-implement deserialization in the service classloader.
     */
    fun parseInput(json: String?): T? {
        val type = paramType
        return if (type == null || json.isNullOrBlank()) {
            null
        } else {
            objectMapper.readValue(json, type)
        }
    }

    /**
     * Deserialize raw JSON produced by the LLM and execute the tool.
     *
     * Called from the service layer (app classloader) with the raw JSON string that
     * Spring AI extracted from the LLM response. Deserialization is performed here,
     * inside the plugin classloader, so [paramType] is always resolvable without
     * crossing classloader boundaries.
     *
     * @param json Raw JSON string from the LLM (e.g. `{"timezone":"UTC"}`)
     * @param context The execution context for this tool call
     * @return The execution result as a String
     */
    fun executeWithJson(
        json: String?,
        context: ToolContext,
    ): String = execute(parseInput(json), context)

    // ─── User-confirmation opt-in (WZ-31596) ───────────────────────────────────────────────
    //
    // A tool opts in to the confirmation flow by overriding [requiresConfirmation] to
    // return `true` for the inputs that need explicit user validation. The orchestrator
    // then routes via the [PendingConfirmationEvent] persistence cycle and calls
    // [executeWithConfirmation] (post-confirmation) or [onRejected] (post-refusal).
    //
    // When [requiresConfirmation] returns `false` (the default), the tool runs through
    // its standard [execute] path — same as a tool that never opted in.

    /**
     * When `true`, the orchestrator skips the implicit-consent check (`shouldConfirm`)
     * and always asks the user for an explicit confirmation prompt. Set this on tools
     * whose side-effects are destructive enough that the LLM-judged "user already
     * authorised" path is unsafe (e.g. file deletion, irreversible writes).
     */
    val bypassImplicitConsent: Boolean get() = false

    /**
     * Returns `true` if this specific call requires explicit user confirmation before any
     * side-effect is applied. Single opt-in for the confirmation flow — a tool that never
     * overrides this method behaves as a non-confirmation tool.
     */
    fun requiresConfirmation(
        input: T?,
        context: ToolContext,
    ): Boolean = false

    /**
     * Instructions appended to the `analyzeConfirmation` prompt to guide the LLM judge
     * when interpreting the user's free-form reply. For destructive actions this SHOULD
     * be a strict prompt (e.g. "Require explicit confirmation: a bare 'ok' is not enough
     * unless the previous turn clearly described the destructive action."). Plugin authors
     * MUST NOT include user-controlled strings here.
     */
    fun getConfirmationInstructions(): String = ""

    /**
     * Applies the actual side-effect after the user confirmed. Receives the same typed
     * [input] that [requiresConfirmation] saw — the input is JSON-round-tripped via the
     * [PendingConfirmationEvent.inputJson] so the call is replay-safe across server
     * restarts.
     *
     * Default delegates to [execute] — suitable for tools whose only difference between
     * "with confirmation" and "without" is the orchestrator's gating step. Override when
     * the post-confirmation path must differ (e.g. freeze a resolved snapshot, skip
     * permission checks already done, etc.).
     *
     * Idempotence note: implementations SHOULD be safe to re-invoke on the same input
     * (the orchestrator guards with `shouldContinue()`, but cancellation can race).
     */
    fun executeWithConfirmation(
        input: T?,
        context: ToolContext,
    ): String = execute(input, context)

    /**
     * Called when the user rejected the action. Default returns a generic cancellation
     * message; override if the tool wants to log/clean up or produce a custom message.
     */
    fun onRejected(): String = "Action cancelled."

    companion object {
        private val objectMapper = jacksonObjectMapper()
    }
}
