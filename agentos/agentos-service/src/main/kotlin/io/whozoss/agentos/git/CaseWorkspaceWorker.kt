package io.whozoss.agentos.git

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives requested namespace checkouts and, through [CaseWorkspaceSweep], case workspaces to
 * readiness.
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
    /** Null without `agentos.git.workspaces.enabled`: the worker then only prepares namespace checkouts. */
    private val workspaceSweep: CaseWorkspaceSweep?,
    private val associationService: GitRepositoryAssociationService,
    private val checkoutService: RepositoryCheckoutService,
    private val checkoutProvisioner: RepositoryCheckoutProvisioner,
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

    /** Requeue, once at startup, the workspaces a crash left mid-preparation. */
    @EventListener(ApplicationReadyEvent::class)
    fun reclaimInterruptedPreparations() {
        workspaceSweep?.reclaimInterruptedPreparations()
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
            workspaceSweep?.cleanupDeletedCases(::stopRequested)
            if (stopRequested()) return
            prepareRequestedCheckouts()
            if (stopRequested()) return
            workspaceSweep?.let(::prepareRequestedWorkspaces)
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

    /** Prepare the oldest requested case workspaces, one at a time. */
    private fun prepareRequestedWorkspaces(sweep: CaseWorkspaceSweep) {
        val pending = sweep.requested(BATCH_SIZE)
        if (pending.isNotEmpty()) logger.info { "Provisioning ${pending.size} pending workspace(s)" }
        pending.forEach {
            if (stopRequested()) return
            current = "workspace ${it.rootCaseId}" to Instant.now()
            try {
                sweep.provision(it)
            } finally {
                current = null
            }
        }
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

        /** Counted by [CaseWorkspaceSweep], which prepares the case worktrees. */
        internal const val OPERATION_WORKTREE = "worktree"

        private val OPERATIONS = listOf(OPERATION_SWEEP, OPERATION_CHECKOUT, OPERATION_WORKTREE)

        /** Bound each pass so pending work and cleanup alternate regularly. */
        private const val BATCH_SIZE = 5
    }
}
