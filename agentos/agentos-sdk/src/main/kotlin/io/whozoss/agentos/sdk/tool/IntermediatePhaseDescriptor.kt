package io.whozoss.agentos.sdk.tool

/**
 * Describes an intermediate enrichment phase for multi-step parameter preparation.
 *
 * Returned by [StandardTool.getIntermediatePhaseDescriptor] to provide the orchestrator
 * with both the JSON Schema the LLM must fill and the prompt guiding that generation.
 *
 * @param inputSchema JSON Schema for the parameters the LLM must produce in this phase.
 * @param prompt Instructions for the LLM describing what to generate for this phase.
 */
data class IntermediatePhaseDescriptor(
    val inputSchema: String,
    val prompt: String,
)
