package io.whozoss.agentos.delegation

import io.whozoss.agentos.caseFlow.CaseRuntime
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
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
     * Preconditions (checked in order — confinement before state):
     * 1. The sub-case identified by [subCaseId] must exist.
     * 2. The sub-case must be a direct child of [parentCaseId] (ownership check).
     * 3. The sub-case and the parent must share the same namespace (tenant isolation).
     * 4. The sub-case must be in [io.whozoss.agentos.sdk.caseFlow.CaseStatus.IDLE].
     * 5. [agentName] must be in [allowedAgents] (secondary consistency check).
     *
     * Error messages are designed to be actionable for a legitimate LLM caller
     * without disclosing information about cases the caller does not own
     * (e.g. the true parent or namespace of a foreign sub-case are never revealed).
     *
     * Throws [IllegalStateException] if any precondition is violated.
     * Returns the live [CaseRuntime] of the resumed sub-case.
     */
    fun resumeSubCase(
        subCaseId: UUID,
        parentCaseId: UUID,
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

    /** Persist and emit a durable observation on a parent case without re-entering its runtime. */
    fun emitParentEvent(event: CaseEvent) {
        // Default keeps existing alternative implementations source-compatible.
    }
}
