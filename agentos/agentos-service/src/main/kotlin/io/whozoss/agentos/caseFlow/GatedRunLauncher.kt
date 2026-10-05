package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mu.KLogging
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent turns held back by a [CaseLaunchGate]: marked PENDING, then started once the gate admits them.
 *
 * [CaseServiceImpl] creates one only when a gate is installed; without a gate every run starts
 * immediately, as it always did. `synchronized(runtime)` is the admission lock: Stop and Kill cannot
 * fall between taking a turn and publishing its launch.
 */
internal class GatedRunLauncher(
    private val gate: CaseLaunchGate,
    private val scope: CoroutineScope,
    private val runtimeOf: (UUID) -> CaseRuntime?,
    private val statusOf: (UUID) -> CaseStatus?,
    private val storeEvent: (CaseEvent) -> CaseEvent,
) {
    /** Launches admitted and not finished yet. */
    private val executionJobs = ConcurrentHashMap<UUID, Job>()

    /** Turns held back in this process. */
    private val deferredRuns = ConcurrentHashMap.newKeySet<UUID>()

    /** Launches admitted that have not finished yet. Exposed for lifecycle tests. */
    val trackedExecutionCount: Int
        get() = executionJobs.size

    fun isAdmitted(caseId: UUID): Boolean = executionJobs[caseId]?.isCompleted == false

    /**
     * Mark the turn PENDING and start it once the gate admits it. The user's message is already
     * persisted, and [resumeIfPending] picks the turn up when the obstacle clears.
     *
     * The claim (in-memory status + deferredRuns) is atomic with the admission lock so a
     * concurrent Stop or Kill cannot fall between the two. Persistence via [publishStatus]
     * runs outside the lock — no blocking I/O under the monitor.
     */
    fun launch(runtime: CaseRuntime) {
        val claimed = synchronized(runtime) {
            runtime.claimPending().also { deferredRuns.add(runtime.id) }
        }
        claimed?.let(runtime::publishStatus)
        admit(runtime)
    }

    fun resumeIfPending(caseId: UUID) {
        // Only turns deferred by this process: a restart never replays an old instruction.
        if (caseId !in deferredRuns) return
        // Policy: a deferred turn whose persisted status is no longer PENDING is silently dropped.
        //
        // This covers the concurrent-message case: if a second message arrives while turn 1 is
        // running, claimPending() returns null (isRunning() is true) and the status stays RUNNING.
        // admitRun re-adds the id to deferredRuns and registers an invokeOnCompletion hook, but
        // when turn 1 completes and this method is called, statusOf returns IDLE — not PENDING —
        // so the deferred turn is removed and dropped.
        //
        // This is deliberate: deferredRuns encodes process-local intent, not a persistent queue.
        // A turn that could not claim PENDING is lost; the user must send a new message.
        // The behaviour is pinned by the test
        // "a second message sent while a gated turn is running is silently dropped".
        if (statusOf(caseId) != CaseStatus.PENDING) {
            deferredRuns.remove(caseId)
            return
        }
        runtimeOf(caseId)?.let { admit(it) }
    }

    /**
     * A launch can be admitted before run() starts: Stop cancels only that launch. An agent already
     * running keeps the runtime's cooperative interruption.
     *
     * The IDLE claim is atomic with deferredRuns removal under the admission lock.
     * Persistence via [publishStatus] runs outside the lock.
     */
    fun interrupt(runtime: CaseRuntime) {
        val claimed = synchronized(runtime) {
            deferredRuns.remove(runtime.id)
            if (!runtime.isRunning()) {
                executionJobs[runtime.id]?.cancel()
                runtime.claimCancelPending()
            } else null
        }
        claimed?.let(runtime::publishStatus)
    }

    fun kill(
        caseId: UUID,
        runtime: CaseRuntime?,
    ) {
        if (runtime == null) {
            deferredRuns.remove(caseId)
            return
        }
        synchronized(runtime) {
            deferredRuns.remove(caseId)
            // A launch admitted but not running must not clear the Kill flag when it enters run().
            if (!runtime.isRunning()) executionJobs[caseId]?.cancel()
            runtime.requestKill()
        }
    }

    fun keepOpenOnShutdown(caseId: UUID): Boolean = gate.keepOpenOnShutdown(caseId)

    /** Stop this process's work on a case kept open, then [release] it under the same lock. */
    fun stopForShutdown(
        runtime: CaseRuntime,
        release: () -> Unit,
    ): Unit =
        synchronized(runtime) {
            deferredRuns.remove(runtime.id)
            runtime.requestKill()
            executionJobs[runtime.id]?.cancel()
            release()
        }

    private fun admit(runtime: CaseRuntime) {
        try {
            // Publish the waiting turn before trying the lock: a completing preparation may resume
            // the family concurrently, and a short lock owner calls back once it is done.
            gate.withAdmission(runtime.id, onAvailable = { scope.launch { resumeIfPending(runtime.id) } }) {
                admitRun(runtime)
            }
        } catch (e: Exception) {
            // The message is already stored: a gate error must not become an HTTP failure.
            failAdmission(runtime, e)
        }
    }

    /**
     * Try to start a deferred turn under the admission lock.
     *
     * The pre-check ([gate.launchDecision] called here, before creating a coroutine) runs inside
     * `synchronized(runtime)` **and** inside the [WorkspaceLifecycleLocks] root lock held by
     * [GitCaseLaunchGate.withAdmission]. This means two Neo4j reads are held under two locks.
     *
     * The trade-off is deliberate:
     * - The lazy job already re-checks [gate.launchDecision] once it starts, because preparation
     *   or cleanup may have changed the workspace state since the pre-check. So the outcome is
     *   identical whether the pre-check is present or not.
     * - The pre-check avoids creating a coroutine when the gate would immediately refuse or
     *   re-defer, keeping [trackedExecutionCount] accurate and lifecycle assertions cheap.
     * - The critical section is short: [gate.launchDecision] on the Git gate resolves two cached
     *   or indexed Neo4j reads (binding status + case status), not a full query.
     *
     * If this becomes a bottleneck, the pre-check can be dropped: the in-job check handles both
     * [LaunchDecision.Wait] and [LaunchDecision.Refuse] correctly, and the only observable
     * difference is that [trackedExecutionCount] transiently reaches 1 for refused turns.
     */
    private fun admitRun(runtime: CaseRuntime): Unit =
        synchronized(runtime) {
            // A worker completion and a lock callback can both try: only one consumes the turn.
            if (!deferredRuns.remove(runtime.id)) return@synchronized
            val preCheck = try { gate.launchDecision(runtime.id) } catch (e: Exception) {
                failAdmission(runtime, e)
                return@synchronized
            }
            when (preCheck) {
                is LaunchDecision.Wait -> { deferredRuns.add(runtime.id); return@synchronized }
                is LaunchDecision.Refuse -> { refuseAdmission(runtime, preCheck.reason); return@synchronized }
                is LaunchDecision.Admit -> startAdmittedJob(runtime)
            }
        }

    /**
     * Creates and starts the lazy coroutine for an admitted turn.
     * Called inside `synchronized(runtime)` after the pre-check has returned [LaunchDecision.Admit].
     * The in-job re-check re-evaluates [gate.launchDecision] outside the lock because preparation
     * or cleanup may have changed the workspace state since the pre-check.
     */
    private fun startAdmittedJob(runtime: CaseRuntime) {
        var admittedJob: Job? = null
        var finishingJob: Job? = null
        executionJobs.compute(runtime.id) { _, previous ->
            if (previous?.isCompleted == false) {
                // A stopped launch may still be finishing: keep this turn for its completion.
                deferredRuns.add(runtime.id)
                finishingJob = previous
                previous
            } else {
                // Lazy, so the job is published before it can complete and clean up.
                scope.launch(start = CoroutineStart.LAZY) {
                    // Preparation or cleanup may have started since the pre-check admission.
                    when (val decision = try { gate.launchDecision(runtime.id) } catch (e: Exception) {
                        failAdmission(runtime, e)
                        return@launch
                    }) {
                        is LaunchDecision.Admit -> runtime.run()
                        is LaunchDecision.Wait -> deferredRuns.add(runtime.id)
                        is LaunchDecision.Refuse -> refuseAdmission(runtime, decision.reason)
                    }
                }.also { admittedJob = it }
            }
        }
        finishingJob?.invokeOnCompletion { scope.launch { resumeIfPending(runtime.id) } }
        admittedJob?.let { job ->
            job.invokeOnCompletion { executionJobs.remove(runtime.id, job) }
            job.start()
        }
    }

    /**
     * The gate could not decide due to a technical error: never start the run, tell the user
     * and return to IDLE. The turn may be retried once the error is resolved.
     */
    private fun failAdmission(
        runtime: CaseRuntime,
        cause: Exception,
    ) {
        logger.error(cause) { "Could not check whether case ${runtime.id} may run; its instruction was not started" }
        deferredRuns.remove(runtime.id)
        runtime.emitEvent(
            storeEvent(
                WarnEvent(
                    namespaceId = runtime.namespaceId,
                    caseId = runtime.id,
                    message = "This message could not be started because its workspace could not be checked. Send it again.",
                ),
            ),
        )
        // Claim the IDLE transition atomically (no lock needed here — deferredRuns is already
        // removed and we are the only caller on this path), then persist outside any lock.
        runtime.claimCancelPending()?.let(runtime::publishStatus)
    }

    /**
     * The gate permanently refuses the run (e.g. workspace `FAILED`, `DELETING`, `REMOVED`):
     * emit a [WarnEvent] and return the case to `IDLE`. Unlike [failAdmission], this is a
     * deliberate gate decision, not a technical error — no retry is expected.
     */
    private fun refuseAdmission(
        runtime: CaseRuntime,
        reason: String,
    ) {
        logger.warn { "Case ${runtime.id} was permanently refused by the gate: $reason" }
        deferredRuns.remove(runtime.id)
        runtime.emitEvent(
            storeEvent(
                WarnEvent(
                    namespaceId = runtime.namespaceId,
                    caseId = runtime.id,
                    message = "This message could not be started because its workspace is unavailable: $reason",
                ),
            ),
        )
        runtime.claimCancelPending()?.let(runtime::publishStatus)
    }

    companion object : KLogging()
}
