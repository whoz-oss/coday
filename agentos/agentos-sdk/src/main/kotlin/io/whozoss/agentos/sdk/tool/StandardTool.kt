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
    // A tool opts in to the confirmation flow by overriding [getConfirmationMode] to return
    // a value other than [ConfirmationMode.NONE]. The orchestrator then routes via the
    // [PendingConfirmationEvent] persistence cycle and calls [executeWithJson]
    // (post-confirmation) or [onRejected] (post-refusal).
    //
    // When [getConfirmationMode] returns [ConfirmationMode.NONE] (the default), the tool runs
    // through its standard [execute] path — same as a tool that never opted in.

    /**
     * Resolution of the confirmation mode. The orchestrator calls this with the proposed args
     * and current case events before deciding whether to gate the tool call.
     *
     * - [ConfirmationMode.NONE]: no confirmation required — tool executes directly (default).
     * - [ConfirmationMode.INFER]: confirmation required, but the orchestrator may skip
     *   the explicit prompt when it detects implicit consent in the conversation history
     *   (via [ConfirmationManager.shouldConfirm]).
     * - [ConfirmationMode.EVERY_TIME]: confirmation required on every call; implicit consent
     *   is never trusted. Use this for irreversible side-effects (e.g. file deletion).
     *
     * Override to return a constant for a fixed mode:
     * ```kotlin
     * override suspend fun getConfirmationMode(argsJson: String?, context: ToolContext?) =
     *     ConfirmationMode.EVERY_TIME
     * ```
     *
     * Or override with logic to compute the mode dynamically — e.g. inspect prior tool calls
     * in [ToolContext.caseEvents] to bypass confirmation when an in-session create makes a
     * follow-up update implicit.
     *
     * ⚠️ HOT PATH — called on **every** tool call by the orchestrator, including those that
     * resolve to [ConfirmationMode.NONE]. Overrides MUST be:
     *   - **cheap** : no HTTP, no DB call, no LLM. Pure local computation on `argsJson`
     *     and `context.caseEvents` only.
     *   - **side-effect-free** : the orchestrator may call this multiple times for the
     *     same tool call (e.g. on retry). Mutating state, writing logs at INFO+, or
     *     emitting events here will leak / duplicate / mislead.
     *
     * `suspend` is allowed (the orchestrator awaits) so simple async lookups stay possible,
     * but in practice prefer plain Kotlin matching against `context.caseEvents`.
     *
     * @param argsJson Raw JSON args produced by the LLM for the impending tool call
     * @param context Execution context (namespaceId, userId, caseEvents)
     */
    suspend fun getConfirmationMode(
        argsJson: String? = null,
        context: ToolContext? = null,
    ): ConfirmationMode = ConfirmationMode.NONE

    /**
     * Tool-specific guidance injected as a labelled `Tool-specific confirmation guidance:`
     * section into BOTH:
     *   1. the `shouldConfirm` prompt (tour 1 — decide whether to gate)
     *   2. the `analyzeConfirmation` prompt (tour 2 — parse the user's free-form reply)
     *
     * Write phase-agnostic criteria, not imperative overrides. The LLM combines this
     * guidance with the general decision rules of the prompt template.
     *
     * Examples:
     *   "Hesitant replies ('pourquoi pas', 'I guess') are not affirmative consent."
     *   "Updates that overwrite non-empty fields require explicit user authorization."
     *
     * **Intentionally static** — no args, no context, no suspend. The LLM judge already
     * sees the proposed args (`proposedData` in tour 1, `pendingPayload` in tour 2) and
     * the full conversation history, so per-call branching of the prompt would be
     * redundant. For args/context-driven *decisions* (e.g. bypass), override
     * [getConfirmationMode] instead — keep this method as fixed criteria for the LLM.
     *
     * Plugin authors MUST NOT include user-controlled strings here.
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
