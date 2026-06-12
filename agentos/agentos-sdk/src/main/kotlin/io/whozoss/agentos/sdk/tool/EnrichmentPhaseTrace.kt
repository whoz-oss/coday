package io.whozoss.agentos.sdk.tool

/**
 * Captures the inputs and outcome of a single enrichment phase executed by
 * [AgentAdvanced.runEnrichmentPhases]. Attached to [ToolRequestEvent.enrichmentPhases]
 * so the full preparation trace is persisted alongside the final tool arguments.
 *
 * @param phaseIndex Zero-based index of this phase within the enrichment sequence.
 * @param prompt The instruction prompt from [IntermediatePhaseDescriptor.prompt].
 * @param llmOutput The raw JSON the LLM generated for this phase (after fence-stripping).
 * @param enrichmentContent The content returned by [StandardTool.enrich], or null when
 *   the phase failed or the tool returned no content.
 * @param success Whether [StandardTool.enrich] reported success for this phase.
 */
data class EnrichmentPhaseTrace(
    val phaseIndex: Int,
    val prompt: String,
    val llmOutput: String,
    val enrichmentContent: String?,
    val success: Boolean,
)
