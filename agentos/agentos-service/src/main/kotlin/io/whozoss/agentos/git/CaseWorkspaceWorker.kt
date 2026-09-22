package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.ObjectMapper

import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.CaseService
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives requested workspaces to readiness.
 *
 * Creating a case only records the intent; cloning, fetching and setup take minutes and
 * must not happen in a request thread. This sweep picks up the intents and runs them.
 *
 * ## Scope of the guarantee
 *
 * This is a single-instance sweep, matching the deployment model of one AgentOS per workstream. It
 * guards against overlapping passes in the same JVM with an in-process flag, **not** against two
 * instances working the same database: there is no claim, no lease and no fencing token. Running
 * two instances against one database would have both provision the same workspace.
 *
 * What makes that acceptable for now is that [CaseWorktreeProvisioner.ensureReady] is idempotent
 * and the per-root-case constraint prevents duplicate bindings, so a repeat pass converges rather
 * than corrupts. Moving to several instances requires a real claim/lease runner, which is a
 * separate piece of work.
 */
@Component
@ConditionalOnProperty(
    prefix = "agentos.git.worker",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class CaseWorkspaceWorker(
    private val bindingService: CaseResourceBindingService,
    private val associationService: GitRepositoryAssociationService,
    private val provisioner: CaseWorktreeProvisioner,
    private val caseRepository: CaseRepository,
    private val caseService: CaseService,
    private val checkoutService: RepositoryCheckoutService,
    private val checkoutProvisioner: RepositoryCheckoutProvisioner,
    private val objectMapper: ObjectMapper,
    private val lifecycle: GitWorkspaceLifecycleService? = null,
) {
    private val sweeping = AtomicBoolean(false)

    /**
     * Hand back to the sweep any workspace left mid-preparation by a crash.
     *
     * [CaseWorktreeProvisioner.ensureReady] marks a binding `PREPARING` before work that can run for
     * tens of minutes (clone, fetch, `worktree add`, setup). Kill the process in that window and the
     * binding stays `PREPARING` forever, because the sweep only looks at `REQUESTED`. Nothing else
     * re-drives a binding, so the family is bricked: the launch gate defers every message and every
     * file access answers 409, with no error surfaced anywhere.
     *
     * Re-driving reuses the frozen base SHA and the registered worktree, including a branch
     * subsequently created by an agent. `worktree prune` clears stale registrations before creation.
     * An interrupted setup requires explicit acknowledgement before its replay.
     *
     * This runs **at startup only**, not on the timer. A crash is the sole path that leaves
     * `PREPARING` behind — an application failure already lands in `FAILED` — so sweeping that
     * status on every tick would buy nothing and would instead need a lease or a heartbeat to avoid
     * stealing work from a pass still in flight. That reasoning depends on the single-instance model
     * described above: a second instance starting up would hand itself the first one's live work.
     */
    @EventListener(ApplicationReadyEvent::class)
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

    /**
     * Provision the oldest pending workspaces.
     *
     * Only [CaseResourceStatus.REQUESTED] is picked up. A [CaseResourceStatus.FAILED] workspace is
     * deliberately left alone: retrying it on a timer would hammer a misconfigured repository
     * forever and bury the cause under repeated failures. Recovery is an explicit action.
     */
    @Scheduled(
        initialDelayString = "\${agentos.git.worker.initial-delay-ms:15000}",
        fixedDelayString = "\${agentos.git.worker.interval-ms:10000}",
    )
    fun provisionPending() {
        if (!sweeping.compareAndSet(false, true)) return
        try {
            lifecycle?.cleanupDeletedCases()
            prepareRequestedCheckouts()
            val pending = bindingService.findByStatusIn(listOf(CaseResourceStatus.REQUESTED), BATCH_SIZE)
            if (pending.isEmpty()) return
            logger.info { "Provisioning ${pending.size} pending workspace(s)" }
            pending.forEach(::provisionOne)
        } catch (e: Exception) {
            // A sweep must never die: the next tick has to run.
            logger.error(e) { "Workspace provisioning sweep failed" }
        } finally {
            sweeping.set(false)
        }
    }

    /**
     * Clone the namespace checkouts that an association asked for.
     *
     * The internal bare repository can be prepared before automatic case worktrees are enabled.
     * Its files never appear in the Namespace Exchange.
     *
     * `PREPARING` is the queue here, because that is the state a fresh row carries.
     * [RepositoryCheckoutProvisioner.ensureReady] returns immediately when the root is already a
     * clone, so a row the sweep revisits costs a stat, not a second clone. A `FAILED` checkout is
     * left alone for the same reason a failed workspace is.
     */
    private fun prepareRequestedCheckouts() {
        val requested = checkoutService.findByStatusIn(listOf(RepositoryCheckoutStatus.PREPARING), BATCH_SIZE)
        requested.forEach { checkout ->
            try {
                val settings =
                    associationService.findSettings(checkout.namespaceId)
                        ?: run {
                            checkoutService.markStatus(
                                checkout.id,
                                RepositoryCheckoutStatus.FAILED,
                                "The namespace is no longer associated with a repository",
                            )
                            return@forEach
                        }
                checkoutProvisioner.ensureReady(settings)
            } catch (e: Exception) {
                // ensureReady already recorded FAILED with the cause; keep going through the batch.
                logger.error(e) { "Could not prepare the checkout of namespace ${checkout.namespaceId}" }
            }
        }
    }

    private fun provisionOne(binding: CaseResourceBinding) = WorkspaceLifecycleLocks.withRoot(binding.rootCaseId) {
        provisionLocked(binding)
    }

    private fun provisionLocked(requested: CaseResourceBinding) {
        val binding = bindingService.findByRootCaseId(requested.rootCaseId) ?: return
        if (binding.status != CaseResourceStatus.REQUESTED) return
        // Deletion may have happened after the pending batch was read.
        if (lifecycle?.cleanupDeleted(binding.rootCaseId)?.status in setOf(CaseResourceStatus.DELETING, CaseResourceStatus.REMOVED)) return
        try {
            val rootCase =
                caseRepository.findByIds(listOf(binding.rootCaseId), withRemoved = true).firstOrNull()
                    ?: run {
                        // The case vanished under its binding. Nothing to provision, and leaving it
                        // REQUESTED would make the sweep retry it forever.
                        logger.warn { "Binding ${binding.id} references missing case ${binding.rootCaseId}; marking it failed" }
                        bindingService.markStatus(binding.id, CaseResourceStatus.FAILED, "The owning case no longer exists")
                        return
                    }

            val settings =
                binding.settingsJson?.let { objectMapper.readValue(it, GitRepositorySettings::class.java) }
                    ?: associationService.findSettings(binding.namespaceId)
                    ?: run {
                        logger.warn { "Namespace ${binding.namespaceId} is no longer associated with a repository" }
                        bindingService.markStatus(
                            binding.id,
                            CaseResourceStatus.FAILED,
                            "The namespace is no longer associated with a repository",
                        )
                        return
                    }

            val ready = provisioner.ensureReady(binding, settings, rootCase)

            if (ready.status == CaseResourceStatus.READY) {
                releaseHeldTurns(binding.rootCaseId)
            }
        } catch (e: Exception) {
            // ensureReady already recorded FAILED with the cause; keep going through the batch so
            // one broken workspace does not block every other one behind it.
            logger.error(e) { "Could not provision workspace ${binding.id} for case ${binding.rootCaseId}" }
        }
    }

    /**
     * Release every turn the launch gate held back for this family, not just the root's.
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
        /** Bounded so one sweep cannot monopolise the scheduler for an unbounded time. */
        private const val BATCH_SIZE = 5

        /**
         * Startup reconciliation looks wider than one sweep: every binding a crash stranded has to
         * come back, not just the first few. Still bounded, so a pathological database cannot make
         * startup walk an unbounded result set.
         */
        private const val RECLAIM_LIMIT = 500
    }
}
