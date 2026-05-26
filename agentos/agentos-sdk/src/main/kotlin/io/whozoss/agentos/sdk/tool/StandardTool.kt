package io.whozoss.agentos.sdk.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

interface StandardTool<T> {
    val name: String
    val description: String
    val inputSchema: String
    val version: String
    val paramType: Class<T>?

    suspend fun execute(
        input: T?,
        context: ToolContext,
    ): String

    /**
     * Parse the raw JSON the LLM produced into the tool's typed input.
     *
     * Runs in the plugin classloader, so [paramType] is always resolvable. Helper utility
     * for tools that need the typed input inside a hook implementation (e.g. to read a
     * field from a `requiresConfirmation` body). Not called by the orchestrator in the
     * standard flow — the orchestrator stays string-based and lets the tool parse on its
     * own terms via [executeWithJson] or by calling this method explicitly.
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
    suspend fun executeWithJson(
        json: String?,
        context: ToolContext,
    ): String = execute(parseInput(json), context)

    // ─── User-confirmation opt-in (WZ-31596) ───────────────────────────────────────────────
    //
    // A tool opts in to the confirmation flow by overriding [requiresConfirmation] to
    // return `true` for the inputs that need explicit user validation. The orchestrator
    // then routes via the [PendingConfirmationEvent] persistence cycle and calls
    // [executeWithJson] (post-confirmation) or [onRejected] (post-refusal).
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
     *
     * The orchestrator passes the raw JSON args produced by the LLM. Tools that need a
     * contextual check on the parsed input can call `this.parseInput(argsJson)` locally;
     * most tools that opt in are destructive enough to simply return `true`.
     */
    fun requiresConfirmation(
        argsJson: String?,
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
     * Called when the user rejected the action. Default returns a generic cancellation
     * message; override if the tool wants to log/clean up or produce a custom message.
     */
    fun onRejected(): String = "Action cancelled."

    companion object {
        private val objectMapper = jacksonObjectMapper()
    }
}
