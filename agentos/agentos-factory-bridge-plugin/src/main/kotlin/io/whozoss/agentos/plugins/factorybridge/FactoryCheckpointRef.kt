package io.whozoss.agentos.plugins.factorybridge

/**
 * Opaque reference to a Factory human-checkpoint interaction that a question was opened for.
 *
 * The Factory Bridge owns this concept end-to-end: it is produced by
 * [io.whozoss.agentos.plugins.factorybridge.tools.FactoryRequestHumanDecisionTool] from the
 * interaction-open response and consumed by [FactoryAnswerInterceptor] to submit the user's
 * decision to the Factory before the host persists the answer.
 *
 * It deliberately lives in the plugin, not in `agentos-sdk`, so that no Factory-specific
 * type leaks into the AgentOS core or SDK. All three fields are server-authoritative — they
 * are never supplied by the model.
 */
data class FactoryCheckpointRef(
    val workflowId: String,
    val interactionId: String,
    val interactionRevision: Long,
)
