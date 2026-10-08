package io.whozoss.agentos.workflow

import java.util.UUID

/**
 * Narrow contract for starting a standalone case from inside an agent run.
 *
 * Carried by [AgentExecutionContext] rather than injected as a Spring bean: the implementation
 * is [io.whozoss.agentos.caseFlow.CaseServiceImpl], which itself depends on [AgentService].
 * Injecting it into the agent layer would create the cycle `CaseService → AgentService → CaseService`
 * — same reasoning as [io.whozoss.agentos.delegation.SubCaseManager].
 *
 * Unlike a sub-case, a launched case has no parent link: its lifecycle is independent from the
 * case that launched it (killing the launcher case does not kill it).
 */
fun interface CaseLauncherService {
    /**
     * Create a case owned by [onBehalfOfUserId], send [task] to [agentName] as its first message
     * and start it. Returns immediately with the new case id; the case runs in the background.
     *
     * [sessionContext] is forwarded to [io.whozoss.agentos.caseFlow.CaseService.addMessage] as-is.
     * When provided it is embedded on the first [io.whozoss.agentos.sdk.caseEvent.MessageEvent] and
     * injected into the LLM prompt (e.g. business context, preferred language).
     */
    fun launchCase(
        namespaceId: UUID,
        agentName: String,
        task: String,
        onBehalfOfUserId: UUID,
        sessionContext: Map<String, Any?>?,
    ): UUID
}
