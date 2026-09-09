package io.whozoss.agentos.delegation

import io.whozoss.agentos.caseFlow.CaseRuntime
import java.util.UUID

/**
 * Minimal contract for launching and managing sub-cases from within a tool.
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
interface SubCaseManager {
    /**
     * Create a new sub-case under [parentCaseId], inject [task] as the first user message,
     * start the execution loop, and return the live [CaseRuntime].
     */
    fun startSubCase(
        parentCaseId: UUID,
        namespaceId: UUID,
        agentName: String,
        task: String,
        userId: UUID,
    ): CaseRuntime

    /**
     * Resume an existing IDLE sub-case by injecting [task] as a new user message.
     *
     * The sub-case must:
     * - exist and be in [io.whozoss.agentos.sdk.caseFlow.CaseStatus.IDLE]
     * - have [agentName] in [allowedAgents]
     *
     * Throws [IllegalArgumentException] if any precondition is violated.
     * Returns the live [CaseRuntime] of the resumed sub-case.
     */
    fun resumeSubCase(
        subCaseId: UUID,
        agentName: String,
        task: String,
        userId: UUID,
        allowedAgents: List<String>,
    ): CaseRuntime

    /**
     * Permanently terminate a case that did not complete in time.
     * Called by [DelegationTool] after a timeout to avoid leaving orphan runtimes in memory.
     */
    fun killCase(caseId: UUID)
}
