package io.whozoss.agentos.agentConfig

import java.util.UUID

/** Published by [AgentConfigService.delete] once the agent [agentConfigId] is deleted: what serves it goes too. */
data class AgentConfigDeletedEvent(
    val agentConfigId: UUID,
)
