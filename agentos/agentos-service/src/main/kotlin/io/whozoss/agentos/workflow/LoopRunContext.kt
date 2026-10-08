package io.whozoss.agentos.workflow

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.user.User
import java.util.UUID

/**
 * Everything an [io.whozoss.agentos.agent.AgentLoop] run needs from its case and agent definition.
 *
 * @param triggerUser  The user who launched the run, or null when it cannot be resolved.
 * @param tools        Tools resolved for the loop agent — the Search tool is looked up here.
 */
data class LoopRunContext(
    val namespaceId: UUID,
    val caseId: UUID,
    val agentName: String,
    val triggerUser: User?,
    val tools: Collection<StandardTool<*>>,
    val caseEvents: List<CaseEvent>,
)
