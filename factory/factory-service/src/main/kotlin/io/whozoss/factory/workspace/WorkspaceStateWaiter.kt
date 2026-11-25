package io.whozoss.factory.workspace

import io.whozoss.factory.persistence.TenantScope
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Waits for a run's workspace to become usable, WITHOUT holding a database
 * transaction (the polling loop is deliberately not `@Transactional` — the
 * status reads own their short transactions).
 *
 * Behaviour on each observed [WorkspaceSnapshot]:
 *  - `null` (no persisted environment found) -> returns `null`, not permission
 *    to use another directory. [RunWorktreeResolver] refuses every code step in
 *    enabled mode; only the explicit workspace-disabled opt-out permits fallback;
 *  - [WorkspaceStatus.PREPARING] -> keeps polling until the timeout;
 *  - [WorkspaceStatus.READY] -> returns the exact [WorkspaceReadyFacts]
 *    (worktree path). A `READY` workspace without a path is a contradiction and
 *    fails with [WorkspaceProvisioningCodes.WORKSPACE_PATH_MISSING];
 *  - [WorkspaceStatus.FAILED] -> throws immediately with the actionable cause.
 *
 * The waiter never selects a different directory. READY describes provisioning
 * availability; [RunWorktreeResolver] additionally validates the local Git root,
 * case/namespace binding and branch/HEAD before code execution.
 *
 * ## Source of truth
 * The run worktree is read from the Factory-owned
 * [io.whozoss.factory.environment.domain.WorkEnvironment] (via
 * [WorkspaceStatePort]). Parent-run and standalone provisioning share adoption of
 * the root case's READY path from AgentOS workspace/changes into that environment. This
 * waiter reads the persisted environment, not an inferred namespace parent.
 */
@Service
class WorkspaceStateWaiter(
    private val statePort: WorkspaceStatePort,
    private val properties: WorkspaceProperties = WorkspaceProperties(),
) {

    private val logger = KotlinLogging.logger {}

    /**
     * Awaits the run's workspace, returning the proven worktree facts or `null`
     * when no environment exists or workspace checks are disabled. Null is not
     * a fallback authorization; [RunWorktreeResolver] enforces the code-step policy.
     * Throws [WorkspaceProvisioningException] on failed preparation or timeout.
     */
    fun awaitWorkspaceReady(
        scope: TenantScope,
        workflowId: String,
        timeoutMs: Long = properties.readyTimeoutMs,
        pollIntervalMs: Long = properties.readyPollIntervalMs,
    ): WorkspaceReadyFacts? {
        if (!properties.enabled) return null
        return poll(scope, workflowId, timeoutMs, pollIntervalMs, System::currentTimeMillis, { Thread.sleep(it) })
    }

    /** Testable core: the clock and the sleep are injected so the loop is deterministic. */
    internal fun poll(
        scope: TenantScope,
        workflowId: String,
        timeoutMs: Long,
        pollIntervalMs: Long,
        now: () -> Long,
        sleep: (Long) -> Unit,
    ): WorkspaceReadyFacts? {
        val deadline = now() + timeoutMs
        while (true) {
            val snapshot = statePort.latestForWorkflow(scope, workflowId) ?: return null
            when (snapshot.status) {
                WorkspaceStatus.READY -> {
                    val path = snapshot.worktreePath?.takeIf { it.isNotBlank() }
                        ?: throw WorkspaceProvisioningException(
                            WorkspaceProvisioningCodes.WORKSPACE_PATH_MISSING,
                            "The workspace of workflow '$workflowId' is READY but exposes no worktree path.",
                            details = mapOf("workflowId" to workflowId, "caseId" to snapshot.caseId),
                        )
                    return WorkspaceReadyFacts(
                        worktreePath = path, repoRoot = snapshot.repoRoot, caseId = snapshot.caseId,
                        environmentId = snapshot.environmentId, baseCommit = snapshot.baseCommit, namespaceId = snapshot.namespaceId,
                    )
                }

                WorkspaceStatus.FAILED -> throw WorkspaceProvisioningException(
                    WorkspaceProvisioningCodes.WORKSPACE_PROVISIONING_FAILED,
                    "The workspace of workflow '$workflowId' failed to prepare: " +
                        "${snapshot.failureReason ?: "unknown cause"}.",
                    details = mapOf(
                        "workflowId" to workflowId,
                        "caseId" to snapshot.caseId,
                        "status" to snapshot.status.name,
                        "failureReason" to snapshot.failureReason,
                        "remedy" to "Re-provision the run workspace, then retry.",
                    ),
                )

                WorkspaceStatus.PREPARING -> {
                    if (now() >= deadline) {
                        throw WorkspaceProvisioningException(
                            WorkspaceProvisioningCodes.WORKSPACE_PREPARING_TIMEOUT,
                            "The workspace of workflow '$workflowId' is still PREPARING after ${timeoutMs}ms.",
                            details = mapOf(
                                "workflowId" to workflowId,
                                "caseId" to snapshot.caseId,
                                "timeoutMs" to timeoutMs,
                                "remedy" to "Check the workspace provisioner before retrying.",
                            ),
                        )
                    }
                    logger.debug { "Workspace of workflow '$workflowId' is PREPARING; waiting ${pollIntervalMs}ms" }
                    sleep(pollIntervalMs)
                }
            }
        }
    }
}
