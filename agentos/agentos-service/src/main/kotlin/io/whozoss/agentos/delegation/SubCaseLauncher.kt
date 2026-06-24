package io.whozoss.agentos.delegation

import io.whozoss.agentos.caseFlow.CaseRuntime
import java.util.UUID

/**
 * Minimal contract for launching a sub-case from within a tool.
 *
 * Extracted from [io.whozoss.agentos.caseFlow.CaseService] to break the circular
 * dependency between [io.whozoss.agentos.agent.AgentServiceImpl] and
 * [io.whozoss.agentos.caseFlow.CaseService]:
 *
 *   CaseService → AgentService → CaseService  (cycle)
 *   CaseService → AgentService → SubCaseLauncher  (no cycle)
 *
 * [io.whozoss.agentos.caseFlow.CaseServiceImpl] implements this interface alongside
 * [io.whozoss.agentos.caseFlow.CaseService] — no logic is duplicated.
 */
interface SubCaseLauncher {
    fun startSubCase(
        parentCaseId: UUID,
        namespaceId: UUID,
        agentName: String,
        task: String,
        userId: UUID,
    ): CaseRuntime

    /**
     * Permanently terminate a sub-case that did not complete in time.
     * Called by [DelegationTool] after a timeout to avoid leaving orphan runtimes in memory.
     */
    fun killSubCase(caseId: UUID)
}
