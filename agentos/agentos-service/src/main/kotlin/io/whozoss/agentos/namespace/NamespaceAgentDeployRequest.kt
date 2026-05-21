package io.whozoss.agentos.namespace

import java.util.UUID

data class NamespaceAgentDeployRequest(val agentIds: List<UUID> = emptyList())