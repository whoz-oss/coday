package io.whozoss.agentos.git

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Observes stored resources even when no conversation has an in-memory runtime or SSE subscriber.
 * Like all Git background work, it runs only with the worker, so the worker switch stops it too.
 */
@Component
@ConditionalOnExpression(
    "'\${agentos.git.workspaces.enabled:false}'.equalsIgnoreCase('true') and '\${agentos.git.worker.enabled:false}'.equalsIgnoreCase('true')",
)
class GitWorkspaceMonitor(
    private val bindings: CaseResourceBindingService,
    private val roots: GitExchangeRootResolver,
    private val statuses: GitWorkspaceStatusService,
    private val executor: GitWorkRunner = GitWorkRunner { it.run() },
    private val meterRegistry: MeterRegistry = SimpleMeterRegistry(),
    private val control: GitWorkspacesControl = GitWorkspacesControl(),
) {
    private val active = AtomicBoolean()

    init {
        // Registered up front so an instance without failures reports 0 rather than no data.
        meterRegistry.timer(SWEEP_TIMER)
        meterRegistry.counter(ERROR_COUNTER)
    }

    /**
     * Where the next sweep starts. Sweeps never overlap, but consecutive ones may run on different
     * pool threads: volatile keeps the cursor visible to the next one without relying on how the
     * sweep guard orders them.
     */
    @Volatile
    private var cursor: CaseResourceBindingCursor? = null

    @Scheduled(
        fixedDelayString = "\${agentos.git.status.interval-ms:60000}",
        initialDelayString = "\${agentos.git.status.initial-delay-ms:30000}",
    )
    fun poll() {
        if (control.isMonitorPaused()) return
        submitWorkspaceSweep(executor, active) { pollBatch() }
    }

    private fun pollBatch() {
        val sample = Timer.start(meterRegistry)
        try {
            val page = bindings.findByStatusIn(READY_ONLY, PAGE_SIZE, cursor)
            // An exhausted cursor means the last page is behind us: restart from the beginning.
            val batch = if (page.isEmpty() && cursor != null) bindings.findByStatusIn(READY_ONLY, PAGE_SIZE) else page
            cursor = batch.takeIf { it.size == PAGE_SIZE }?.last()?.let(CaseResourceBindingCursor::after)
            // A pause or a shutdown stops this page: the cursor already points past it.
            batch.asSequence().takeWhile { !stopRequested() }.forEach(::observe)
        } catch (e: Exception) {
            countError()
            logger.warn(e) { "Workspace status sweep failed" }
        } finally {
            sample.stop(meterRegistry.timer(SWEEP_TIMER))
        }
    }

    /** Checked between workspaces: a pause or a shutdown never interrupts the observation in progress. */
    private fun stopRequested(): Boolean = control.isMonitorPaused() || Thread.currentThread().isInterrupted

    private fun observe(binding: CaseResourceBinding) {
        try {
            val repository = roots.resolveGit(binding.rootCaseId).repositoryPath.toAbsolutePath().normalize()
            // The service records an unavailable status rather than throwing: count both.
            if (statuses.refresh(binding, repository).summary?.error != null) countError()
        } catch (e: Exception) {
            countError()
            logger.warn(e) { "Workspace status refresh failed for ${binding.rootCaseId}" }
        }
    }

    private fun countError() = meterRegistry.counter(ERROR_COUNTER).increment()

    companion object : KLogging() {
        /** Duration and count of status sweeps. */
        const val SWEEP_TIMER = "agentos.git.monitor.sweep"

        /** Observations that ended with an unavailable status or failed outright, and failed sweeps. */
        const val ERROR_COUNTER = "agentos.git.monitor.errors"

        /** Small enough that a paused operator waits at most this many slow observations. */
        private const val PAGE_SIZE = 5
        private val READY_ONLY = listOf(CaseResourceStatus.READY)
    }
}
