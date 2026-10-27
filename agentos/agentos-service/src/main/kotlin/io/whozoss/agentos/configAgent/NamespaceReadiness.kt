package io.whozoss.agentos.configAgent

/**
 * What a member's message meets in a namespace, read for one user.
 *
 * @property defaultAgentSource `namespace` (its defaultAgentName), `platform` (agentos.defaults.agent-name)
 *   or null when neither is set.
 * @property resolvedAgent The agent a message without `@mention` goes to; the runtime matches the name as a
 *   prefix, so it may differ from [defaultAgentName].
 */
data class NamespaceReadiness(
    val defaultAgentName: String?,
    val defaultAgentSource: String?,
    val defaultAgentStatus: DefaultAgentStatus,
    val hint: String,
    val resolvedAgent: String?,
    val deployedAgents: List<DeployedAgent>,
)
