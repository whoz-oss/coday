/**
 * Captures the inputs and outcome of a single enrichment phase executed during
 * multi-step parameter generation in AgentAdvanced.
 *
 * Hand-written (not generated) — lives in custom/ to survive OpenAPI regeneration.
 * Mirrors the Kotlin `EnrichmentPhaseTrace` data class from agentos-sdk.
 */
export interface EnrichmentPhaseTrace {
  phaseIndex: number
  prompt: string
  llmOutput: string
  enrichmentContent?: string | null
  success: boolean
}
