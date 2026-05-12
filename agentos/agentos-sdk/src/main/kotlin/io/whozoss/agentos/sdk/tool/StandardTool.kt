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
    // A tool may opt in to the user-confirmation flow by overriding [supportsConfirmation]
    // to `true` AND overriding [requiresConfirmation], [getConfirmationPayload],
    // [executeWithConfirmation], and (optionally) [confirmationLabel],
    // [getConfirmationAnalysisInstructions], [onRejected].
    //
    // When the flow is active the tool's regular [execute] is NOT called — the orchestrator
    // routes via [getConfirmationPayload] → user prompt → [executeWithConfirmation] or
    // [onRejected]. See AgentSimple/AgentAdvanced for the orchestration entry points.

    /**
     * Static declaration that this tool implements the confirmation flow. Used by
     * orchestrators (e.g. AgentAdvanced) to detect support without invoking the dynamic
     * [requiresConfirmation]. Tools that override [requiresConfirmation] MUST also set
     * this to `true`.
     */
    val supportsConfirmation: Boolean get() = false

    /**
     * Returns `true` if this specific call requires explicit user confirmation before any
     * side-effect is applied. Evaluated by the orchestrator before [execute]. Conditional
     * on [input] so a tool may confirm only for "dangerous" inputs (e.g. paths outside a
     * safe directory).
     */
    fun requiresConfirmation(
        input: T?,
        context: ToolContext,
    ): Boolean = false

    /**
     * Computes the payload to confirm and persist with the [PendingConfirmationEvent].
     * Called only when [requiresConfirmation] returned true. MUST be side-effect-free:
     * validate, resolve paths, transform — but do NOT mutate external state. Throws if
     * [input] is invalid; the orchestrator will surface that as a regular tool error
     * without emitting any pending event.
     *
     * Returned value MUST be JSON-serializable by the application ObjectMapper and avoid
     * polymorphic Jackson type info (`@JsonTypeInfo(use = Id.CLASS)`).
     */
    fun getConfirmationPayload(
        input: T?,
        context: ToolContext,
    ): Any = throw IllegalStateException("Tool $name opted-in to confirmation but does not implement getConfirmationPayload")

    /**
     * Short human-readable label derived from the validated payload. Used by the
     * orchestrator to build the user-visible confirmation prompt. MUST be derived from
     * the structured payload only — NEVER concatenate free-form user input here, since
     * the label is injected into the LLM context. The orchestrator additionally sanitizes
     * (whitelist + length cap) before injection.
     */
    fun confirmationLabel(pendingPayload: Any): String = "Confirm $name"

    /**
     * Instructions appended to the analyzeConfirmation prompt. Use this to be more or
     * less strict about what counts as confirmation. For destructive actions this SHOULD
     * be a non-empty strict prompt (e.g. "Require explicit confirmation: a bare 'ok' is
     * not enough unless the previous turn clearly described the destructive action.").
     * Plugin authors MUST NOT include user-controlled strings here.
     */
    fun getConfirmationAnalysisInstructions(): String = ""

    /**
     * Applies the actual side-effect after the user confirmed. [pendingPayload] is the
     * JSON-round-tripped object that [getConfirmationPayload] returned, deserialized as
     * a generic Map. Tools convert it back to their typed payload via
     * `objectMapper.convertValue(pendingPayload, MyPayload::class.java)` — this keeps
     * deserialization inside the plugin classloader.
     */
    fun executeWithConfirmation(
        pendingPayload: Any,
        context: ToolContext,
    ): String = throw IllegalStateException("Tool $name opted-in to confirmation but does not implement executeWithConfirmation")

    /**
     * Called when the user rejected the action. [pendingPayload] is the same Map shape as
     * in [executeWithConfirmation]; [userMessage] is the literal text the user replied
     * with, for human-friendly logging.
     */
    fun onRejected(
        pendingPayload: Any,
        userMessage: String,
        context: ToolContext,
    ): String = "Action cancelled. User said: \"$userMessage\""

    companion object {
        private val objectMapper = jacksonObjectMapper()
    }
}
