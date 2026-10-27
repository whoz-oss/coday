package io.whozoss.agentos.configAgent

/**
 * What a member's message without `@mention` meets in a namespace.
 *
 * @property defaultAgentSource Null when neither the namespace nor the platform names a default agent.
 * @property resolvedAgent The agent such a message goes to; the runtime matches the name as a prefix, so it
 *   may differ from [defaultAgentName].
 */
data class NamespaceReadiness(
    val defaultAgentName: String?,
    val defaultAgentSource: DefaultAgentSource?,
    val defaultAgentStatus: DefaultAgentStatus,
    val hint: String,
    val resolvedAgent: String?,
    val deployedAgents: List<DeployedAgent>,
)
