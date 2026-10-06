package io.whozoss.agentos.git

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.CaseService
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The case workspace part of the Git worker's sweep: requeue preparations a crash interrupted,
 * clean up deleted families, prepare requested workspaces, and hand the turns their families held
 * back to the launch gate.
 *
 * Installed only with `agentos.git.workspaces.enabled`. [CaseWorkspaceWorker] drives it with its own
 * cadence, pause and metrics, and works on namespace checkouts alone without it.
 */
@Component
@ConditionalOnProperty(prefix = "agentos.git.workspaces", name = ["enabled"], havingValue = "true")
class CaseWorkspaceSweep(
    private val bindingService: CaseResourceBindingService,
    private val provisioner: CaseWorktreeProvisioner,
    private val lifecycle: GitWorkspaceLifecycleService,
    private val caseRepository: CaseRepository,
    private val caseService: CaseService,
    private val meterRegistry: MeterRegistry = SimpleMeterRegistry(),
) {
    /**
     * Hand back to the worker's sweep any workspace left mid-preparation by a crash.
     *
     * [CaseWorktreeProvisioner.ensureReady] marks a binding `PREPARING` before work that can run for
     * tens of minutes (clone, fetch, `worktree add`, setup). Kill the process in that window and the
     * binding stays `PREPARING` forever, because the sweep only looks at `REQUESTED`. Nothing else
     * re-drives a binding, so the family is bricked: the launch gate defers every message and every
     * file access answers 409, with no error surfaced anywhere.
     *
     * Re-driving reuses the frozen base SHA and the registered worktree, including a branch
     * subsequently created by an agent. Existing registrations remain intact if their directory is missing.
     * An interrupted setup requires explicit acknowledgement before its replay.
     *
     * This runs **at startup only**, not on the timer. A crash is the sole path that leaves
     * `PREPARING` behind — an application failure already lands in `FAILED` — so sweeping that
     * status on every tick would buy nothing and would instead need a lease or a heartbeat to avoid
     * stealing work from a pass still in flight. That reasoning depends on the single-instance model
     * described above: a second instance starting up would hand itself the first one's live work.
     */
    fun reclaimInterruptedPreparations() {
        try {
            val interrupted = bindingService.findByStatusIn(listOf(CaseResourceStatus.PREPARING), RECLAIM_LIMIT)
            if (interrupted.isEmpty()) return
            logger.warn {
                "Found ${interrupted.size} workspace(s) left preparing by a previous run; queueing them again"
            }
            interrupted.forEach { binding ->
                bindingService.markStatus(binding.id, CaseResourceStatus.REQUESTED, null)
            }
        } catch (e: Exception) {
            // Startup must not fail because reconciliation did: the sweep still works for everything
            // that was REQUESTED, and these bindings stay visible as PREPARING.
            logger.error(e) { "Could not reclaim interrupted workspace preparations" }
        }
    }

    /** Remove the workspaces of deleted families, a few per pass. */
    fun cleanupDeletedCases(stopRequested: () -> Boolean) = lifecycle.cleanupDeletedCases(stopRequested)

    /** The oldest workspaces waiting for preparation. */
    fun requested(limit: Int): List<CaseResourceBinding> =
        bindingService.findByStatusIn(listOf(CaseResourceStatus.REQUESTED), limit)

    /** Prepare one requested workspace, then release its family's held turns once preparation settled. */
    fun provision(binding: CaseResourceBinding) {
        val outcome = WorkspaceLifecycleLocks.withRoot(binding.rootCaseId) { provisionLocked(binding) }
        if (outcome != null && !outcome.isPending) releaseHeldTurns(binding.rootCaseId)
    }

    /** The status this pass left the workspace in, or null when it did not handle the binding. */
    private fun provisionLocked(requested: CaseResourceBinding): CaseResourceStatus? =
        bindingService
            .findByRootCaseId(requested.rootCaseId)
            ?.takeIf { it.status == CaseResourceStatus.REQUESTED }
            ?.let { binding -> removalStarted(binding) ?: prepare(binding) }

    /** Deletion may have happened after the pending batch was read. */
    private fun removalStarted(binding: CaseResourceBinding): CaseResourceStatus? =
        lifecycle.cleanupDeleted(binding.rootCaseId).status.takeIf { it.isRemovalStarted }

    private fun prepare(binding: CaseResourceBinding): CaseResourceStatus =
        try {
            val rootCase = caseRepository.findByIds(listOf(binding.rootCaseId), withRemoved = true).firstOrNull()
            val settings = binding.settings
            when {
                // The case vanished under its binding: left REQUESTED, the sweep would retry it forever.
                rootCase == null -> fail(binding, OWNER_GONE)
                // The family keeps the settings frozen when it was equipped, never the namespace's current ones.
                settings == null -> fail(binding, NO_READABLE_SETTINGS)
                else -> provisioner.ensureReady(binding, settings, rootCase).status
            }
        } catch (e: Exception) {
            // ensureReady records FAILED with the cause. An error before it, such as an unavailable
            // database, leaves the binding REQUESTED for the next pass. Keep going through the batch so
            // one broken workspace does not block every other one behind it. Held turns are handed
            // back to the gate, which reads the binding again: one still pending keeps them waiting.
            meterRegistry.counter(CaseWorkspaceWorker.ERROR_COUNTER, "operation", CaseWorkspaceWorker.OPERATION_WORKTREE)
                .increment()
            logger.error(e) { "Could not provision workspace ${binding.id} for case ${binding.rootCaseId}" }
            CaseResourceStatus.FAILED
        }

    /** Record why preparation cannot happen, so the sweep never picks the binding up again. */
    private fun fail(
        binding: CaseResourceBinding,
        reason: String,
    ): CaseResourceStatus {
        logger.warn { "Workspace ${binding.id} of case ${binding.rootCaseId} cannot be prepared: $reason" }
        return bindingService.markStatus(binding.id, CaseResourceStatus.FAILED, reason).status
    }

    /**
     * Release every turn the launch gate held back for this family, not just the root's.
     *
     * Called once preparation has settled, whatever the outcome: the gate starts held turns on a
     * ready workspace, and refuses them with a warning on a failed or removed one rather than
     * leaving them `PENDING` with no explanation. A refused turn is not replayed after a retry.
     *
     * The whole family shares one workspace, so the gate defers the whole family. A sub-case created
     * by delegation while the workspace was preparing had its message persisted and its run refused
     * exactly like the root's — and resuming only the root left the delegating parent waiting on a
     * child that would never run.
     *
     * [CaseService.resumeIfPending] is the filter: it starts a case only when it is still `PENDING`,
     * so descendants that already ran, were killed or never had a held-back turn are untouched. That
     * is what makes casting this net wider safe.
     */
    private fun releaseHeldTurns(rootCaseId: UUID) {
        caseService.resumeIfPending(rootCaseId)
        val descendants =
            runCatching { caseRepository.findActiveDescendants(rootCaseId) }
                .onFailure { logger.error(it) { "Could not list descendants of $rootCaseId to resume them" } }
                .getOrDefault(emptyList())
        descendants.forEach { caseService.resumeIfPending(it.id) }
    }

    companion object : KLogging() {
        /**
         * Startup reconciliation looks wider than one sweep: every binding a crash stranded has to
         * come back, not just the first few. Still bounded, so a pathological database cannot make
         * startup walk an unbounded result set.
         */
        private const val RECLAIM_LIMIT = 500

        private const val OWNER_GONE = "The owning case no longer exists"

        /** Settings absent or unreadable never come back: a retry fails the same way. */
        private const val NO_READABLE_SETTINGS = "No readable settings were recorded for this workspace. Retrying cannot help."
    }
}
