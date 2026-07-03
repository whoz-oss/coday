package io.whozoss.agentos.sdk.tool

/**
 * Result of an intermediate enrichment phase in multi-step parameter preparation.
 *
 * [content] carries the business data (e.g. file contents, entity JSON, custom field
 * definitions) that AgentAdvanced injects into the LLM prompt for the next phase.
 * When [success] is false, [errorMessage] describes the failure and the orchestrator
 * may abort the preparation loop.
 */
data class EnrichmentResult(
    val success: Boolean,
    val content: String? = null,
    val errorMessage: String? = null,
)
