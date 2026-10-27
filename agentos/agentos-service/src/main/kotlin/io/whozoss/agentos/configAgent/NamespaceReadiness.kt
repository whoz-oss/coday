package io.whozoss.agentos.configAgent

/**
 * What a member's message without `@mention` meets in a namespace.
 *
 * @property defaultAgentSource `namespace` (its defaultAgentName), `platform` (agentos.defaults.agent-name)
 *   or null when neither is set.
 * @property resolvedAgent The agent such a message goes to; the runtime matches the name as a prefix, so it
 *   may differ from [defaultAgentName].
 */
data class NamespaceReadiness(
    val defaultAgentName: String?,
    val defaultAgentSource: String?,
    val defaultAgentStatus: DefaultAgentStatus,
    val hint: String,
    val resolvedAgent: String?,
    val deployedAgents: List<DeployedAgent>,
)
