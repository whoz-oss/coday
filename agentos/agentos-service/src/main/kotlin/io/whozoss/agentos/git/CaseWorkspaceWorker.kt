package io.whozoss.agentos.git

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Prepares requested namespace repositories outside request threads.
 * The in-process guard coordinates one AgentOS instance; multiple instances require a lease.
 *
 * Opt-in: the worker only starts with `agentos.git.worker.enabled=true` (`AGENTOS_GIT_WORKER_ENABLED`).
 * [GitWorkerStartupCheck] logs a notice when the GIT plugin is loaded while it is off. On a live
 * instance, [GitWorkspacesEndpoint] pauses and resumes it through [GitWorkspacesControl].
 */
@Component
@ConditionalOnProperty(prefix = "agentos.git.worker", name = ["enabled"], havingValue = "true", matchIfMissing = false)
class CaseWorkspaceWorker(
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

    /** A clone can take minutes, so the sweep runs on [GitWorkspaceExecutor], never on the scheduler thread. */
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
            prepareRequestedCheckouts()
        } catch (e: Exception) {
            countError(OPERATION_SWEEP)
            logger.error(e) { "Repository provisioning sweep failed" }
        } finally {
            sample.stop(meterRegistry.timer(SWEEP_TIMER))
        }
    }

    /** Checked between items: a pause or a shutdown never interrupts the item in progress. */
    private fun stopRequested(): Boolean = control.isProvisioningPaused() || Thread.currentThread().isInterrupted

    /** Failures by operation, exposed through Actuator next to the sweep timer. */
    private fun countError(operation: String) = meterRegistry.counter(ERROR_COUNTER, "operation", operation).increment()

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

    /** Snapshot of the sweep: whether one is queued or running, and the item it is on. */
    data class WorkerActivity(
        val sweeping: Boolean,
        val currentItem: String?,
        val currentSince: Instant?,
    )

    companion object : KLogging() {
        /** Duration and count of sweeps. */
        const val SWEEP_TIMER = "agentos.git.worker.sweep"

        /** Failed preparations, tagged by `operation`. */
        const val ERROR_COUNTER = "agentos.git.worker.errors"

        private const val OPERATION_SWEEP = "sweep"
        private const val OPERATION_CHECKOUT = "checkout"
        private val OPERATIONS = listOf(OPERATION_SWEEP, OPERATION_CHECKOUT)

        private const val BATCH_SIZE = 5
    }
}
