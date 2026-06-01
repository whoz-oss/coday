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
     * - [ConfirmationMode.INFER]: confirmation required, but the orchestrator may skip
     *   the explicit prompt when it detects implicit consent in the conversation history
     *   (via [ConfirmationManager.shouldConfirm]).
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

    // ─── Parameter preparation for advanced execution ───────────────────────────────────────

    /**
     * Number of intermediate enrichment phases for multi-step parameter preparation.
     * 0 (default) = single-phase generation, identical to current behavior.
     * AgentSimple ignores this. AgentAdvanced orchestrates the phases
     * before generating the final parameters (whose schema is always [inputSchema]).
     */
    suspend fun getIntermediatePhaseCount(): Int = 0

    /**
     * Returns the descriptor for the given intermediate phase, providing both the
     * JSON Schema the LLM must fill and the prompt guiding that generation.
     * Called by AgentAdvanced for phases 0..intermediatePhaseCount-1.
     *
     * @param phaseIndex 0-based index of the intermediate phase
     * @param previousContent content returned by the previous [enrich] call, null for phase 0
     */
    suspend fun getIntermediatePhaseDescriptor(
        phaseIndex: Int,
        previousContent: String?,
    ): IntermediatePhaseDescriptor = throw UnsupportedOperationException("No intermediate phases declared")

    /**
     * Execute an intermediate enrichment phase.
     * Called by AgentAdvanced between phases to fetch business content
     * (e.g. file contents, entity data) needed for subsequent phases.
     *
     * @param phaseIndex 0-based index of the intermediate phase
     * @param phaseParametersJson raw JSON generated by the LLM for this phase
     * @param context standard tool execution context (namespaceId, userId, caseEvents)
     * @return enrichment result with business content for the next phase
     */
    suspend fun enrich(
        phaseIndex: Int,
        phaseParametersJson: String,
        context: ToolContext,
    ): EnrichmentResult = EnrichmentResult(success = true)

    companion object {
        private val objectMapper = jacksonObjectMapper()
    }
}
