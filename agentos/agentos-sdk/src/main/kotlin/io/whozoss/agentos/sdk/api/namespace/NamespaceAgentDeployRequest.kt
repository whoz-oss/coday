package io.whozoss.agentos.sdk.api.namespace

import java.util.UUID

/** Request body of [NamespaceApi.deployAgents] and [NamespaceApi.undeployAgents]: the agents to (un)deploy on the namespace. */
data class NamespaceAgentDeployRequest(
    val agentIds: List<UUID> = emptyList(),
)
