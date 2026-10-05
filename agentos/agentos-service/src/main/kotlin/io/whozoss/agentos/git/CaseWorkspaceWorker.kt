package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.CaseService
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
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
 *
 * Opt-in: the worker only starts with `agentos.git.worker.enabled=true` (`AGENTOS_GIT_WORKER_ENABLED`).
 * [GitWorkerStartupCheck] logs a notice when the GIT plugin is loaded while it is off. On a live
 * instance, [GitWorkspacesEndpoint] pauses and resumes it through [GitWorkspacesControl].
 */
@Component
@ConditionalOnProperty(
    prefix = "agentos.git.worker",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class CaseWorkspaceWorker(
    /** Null without `agentos.git.workspaces.enabled`, like [provisioner]. */
    private val bindingService: CaseResourceBindingService?,
    private val associationService: GitRepositoryAssociationService,
    /** Null without `agentos.git.workspaces.enabled`: the worker then only prepares namespace checkouts. */
    private val provisioner: CaseWorktreeProvisioner?,
    private val caseRepository: CaseRepository,
    private val caseService: CaseService,
    private val checkoutService: RepositoryCheckoutService,
    private val checkoutProvisioner: RepositoryCheckoutProvisioner,
    private val objectMapper: ObjectMapper,
    private val lifecycle: GitWorkspaceLifecycleService? = null,
    private val executor: GitWorkRunner = GitWorkRunner { it.run() },
    private val meterRegistry: MeterRegistry = SimpleMeterRegistry(),
    private val control: GitWorkspacesControl = GitWorkspacesControl(),
) {
    private val sweeping = AtomicBoolean(false)

    @Volatile
    private var current: Pair<String, Instant>? = null

    init {
        // Registered up front so an instance without failures reports 0 rather than no data.
        meterRegistry.timer(SWEEP_TIMER)
        OPERATIONS.forEach { meterRegistry.counter(ERROR_COUNTER, "operation", it) }
    }

    /** What the sweep is doing, for [GitWorkspacesEndpoint]. */
    fun activity(): WorkerActivity = current.let { WorkerActivity(sweeping.get(), it?.first, it?.second) }

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
     * subsequently created by an agent. Existing registrations remain intact if their directory is missing.
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
        val bindings = bindingService ?: return
        try {
            val interrupted = bindings.findByStatusIn(listOf(CaseResourceStatus.PREPARING), RECLAIM_LIMIT)
            if (interrupted.isEmpty()) return
            logger.warn {
                "Found ${interrupted.size} workspace(s) left preparing by a previous run; queueing them again"
            }
            interrupted.forEach { binding ->
                bindings.markStatus(binding.id, CaseResourceStatus.REQUESTED, null)
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
        if (control.isProvisioningPaused()) return
        submitWorkspaceSweep(executor, sweeping) { provisionSweep() }
    }

    private fun provisionSweep() {
        val sample = Timer.start(meterRegistry)
        try {
            lifecycle?.cleanupDeletedCases(::stopRequested)
            if (stopRequested()) return
            prepareRequestedCheckouts()
            val bindings = bindingService
            if (stopRequested() || provisioner == null || bindings == null) return
            val pending = bindings.findByStatusIn(listOf(CaseResourceStatus.REQUESTED), BATCH_SIZE)
            if (pending.isEmpty()) return
            logger.info { "Provisioning ${pending.size} pending workspace(s)" }
            pending.forEach {
                if (stopRequested()) return
                current = "workspace ${it.rootCaseId}" to Instant.now()
                try {
                    provisionOne(it)
                } finally {
                    current = null
                }
            }
        } catch (e: Exception) {
            // A sweep must never die: the next tick has to run.
            countError(OPERATION_SWEEP)
            logger.error(e) { "Workspace provisioning sweep failed" }
        } finally {
            sample.stop(meterRegistry.timer(SWEEP_TIMER))
        }
    }

    /** Checked between items: a pause or a shutdown never interrupts the item in progress. */
    private fun stopRequested(): Boolean = control.isProvisioningPaused() || Thread.currentThread().isInterrupted

    /** Failures by operation, exposed through Actuator next to the sweep timer. */
    private fun countError(operation: String) = meterRegistry.counter(ERROR_COUNTER, "operation", operation).increment()

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
            if (stopRequested()) return
            current = "checkout ${checkout.namespaceId}" to Instant.now()
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
                countError(OPERATION_CHECKOUT)
                logger.error(e) { "Could not prepare the checkout of namespace ${checkout.namespaceId}" }
            } finally {
                current = null
            }
        }
    }

    private fun provisionOne(binding: CaseResourceBinding) {
        val outcome = WorkspaceLifecycleLocks.withRoot(binding.rootCaseId) { provisionLocked(binding) }
        if (outcome != null && !outcome.isPending) releaseHeldTurns(binding.rootCaseId)
    }

    /** The status this pass left the workspace in, or null when it did not handle the binding. */
    private fun provisionLocked(requested: CaseResourceBinding): CaseResourceStatus? {
        val bindings = bindingService ?: return null
        val binding = bindings.findByRootCaseId(requested.rootCaseId) ?: return null
        if (binding.status != CaseResourceStatus.REQUESTED) return null
        // Deletion may have happened after the pending batch was read.
        lifecycle
            ?.cleanupDeleted(binding.rootCaseId)
            ?.status
            ?.takeIf { it == CaseResourceStatus.DELETING || it == CaseResourceStatus.REMOVED }
            ?.let { return it }
        try {
            val rootCase =
                caseRepository.findByIds(listOf(binding.rootCaseId), withRemoved = true).firstOrNull()
                    ?: run {
                        // The case vanished under its binding. Nothing to provision, and leaving it
                        // REQUESTED would make the sweep retry it forever.
                        logger.warn { "Binding ${binding.id} references missing case ${binding.rootCaseId}; marking it failed" }
                        bindings.markStatus(binding.id, CaseResourceStatus.FAILED, "The owning case no longer exists")
                        return CaseResourceStatus.FAILED
                    }

            val settings =
                binding.settingsJson?.let { objectMapper.readValue(it, GitRepositorySettings::class.java) }
                    ?: associationService.findSettings(binding.namespaceId)
                    ?: run {
                        logger.warn { "Namespace ${binding.namespaceId} is no longer associated with a repository" }
                        bindings.markStatus(
                            binding.id,
                            CaseResourceStatus.FAILED,
                            "The namespace is no longer associated with a repository",
                        )
                        return CaseResourceStatus.FAILED
                    }

            return requireNotNull(provisioner).ensureReady(binding, settings, rootCase).status
        } catch (e: Exception) {
            // ensureReady already recorded FAILED with the cause; keep going through the batch so
            // one broken workspace does not block every other one behind it. Held turns are handed
            // back to the gate, which reads the binding again: one still pending keeps them waiting.
            countError(OPERATION_WORKTREE)
            logger.error(e) { "Could not provision workspace ${binding.id} for case ${binding.rootCaseId}" }
            return CaseResourceStatus.FAILED
        }
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

    /** Snapshot of the sweep: whether one is queued or running, and the item it is on. */
    data class WorkerActivity(
        val sweeping: Boolean,
        val currentItem: String?,
        val currentSince: Instant?,
    )

    companion object : KLogging() {
        /** Duration and count of sweeps. */
        const val SWEEP_TIMER = "agentos.git.worker.sweep"

        /** Failed preparations, tagged by `operation`: checkout, worktree or the whole sweep. */
        const val ERROR_COUNTER = "agentos.git.worker.errors"

        private const val OPERATION_SWEEP = "sweep"
        private const val OPERATION_CHECKOUT = "checkout"
        private const val OPERATION_WORKTREE = "worktree"
        private val OPERATIONS = listOf(OPERATION_SWEEP, OPERATION_CHECKOUT, OPERATION_WORKTREE)

        /** Bound each pass so pending work and cleanup alternate regularly. */
        private const val BATCH_SIZE = 5

        /**
         * Startup reconciliation looks wider than one sweep: every binding a crash stranded has to
         * come back, not just the first few. Still bounded, so a pathological database cannot make
         * startup walk an unbounded result set.
         */
        private const val RECLAIM_LIMIT = 500
    }
}
