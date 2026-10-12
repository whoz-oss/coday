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
 * Agent turns held back by a [CaseLaunchGate]: marked [io.whozoss.agentos.sdk.caseFlow.CaseStatus.PENDING],
 * then started once the gate admits them.
 *
 * [CaseServiceImpl] creates one only when a gate is installed; without a gate every run starts
 * immediately, as it always did. `synchronized(runtime)` is the admission lock: Stop and Kill cannot
 * fall between taking a turn and publishing its launch.
 *
 * Note: [io.whozoss.agentos.sdk.caseFlow.CaseStatus.PENDING] here means "a turn is held back by the
 * gate" — it is distinct from [io.whozoss.agentos.sdk.caseFlow.CaseStatus.CREATED], which is the
 * initial status of a freshly created case that has received no message yet.
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
     * Mark the turn [io.whozoss.agentos.sdk.caseFlow.CaseStatus.PENDING] and start it once
     * the gate admits it. The user's message is already persisted, and [resumeIfPending] picks
     * the turn up when the obstacle clears.
     *
     * The claim (in-memory status + deferredRuns) is atomic with the admission lock so a
     * concurrent Stop or Kill cannot fall between the two. Persistence via [publishStatus]
     * runs outside the lock — no blocking I/O under the monitor.
     */
    fun launch(runtime: CaseRuntime) {
        val claimed = synchronized(runtime) {
            runtime.claimPending().also { deferredRuns.add(runtime.id) }
        }
        // A failed PENDING write must not leave the turn deferred with nothing to admit it.
        claimed?.let { publishQuietly(runtime, it) }
        admit(runtime)
    }

    fun resumeIfPending(caseId: UUID) {
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
        when {
            // Only turns deferred by this process: a restart never replays an old instruction.
            caseId !in deferredRuns -> Unit
            statusOf(caseId) != CaseStatus.PENDING -> deferredRuns.remove(caseId)
            else -> runtimeOf(caseId)?.let { admit(it) }
        }
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
        } else {
            synchronized(runtime) {
                deferredRuns.remove(caseId)
                // A launch admitted but not running must not clear the Kill flag when it enters run().
                if (!runtime.isRunning()) executionJobs[caseId]?.cancel()
                runtime.requestKill()
            }
        }
    }

    fun keepOpenOnShutdown(caseId: UUID): Boolean = gate.keepOpenOnShutdown(caseId)

    /**
     * Stop this process's work on a case kept open across a restart. The caller then releases it to
     * `IDLE`, outside the admission lock.
     */
    fun stopForShutdown(runtime: CaseRuntime): Unit =
        synchronized(runtime) {
            deferredRuns.remove(runtime.id)
            // Not a Kill: a turn still running would end as KILLED and close the case for good.
            runtime.requestShutdownStop()
            executionJobs[runtime.id]?.cancel()
        }

    /**
     * First pass of a Stop: cancel a launch admitted but not running yet and mark the runtime
     * interrupted, without releasing a held turn (see [interrupt]).
     */
    fun cancelAdmitted(runtime: CaseRuntime): Unit =
        synchronized(runtime) {
            if (!runtime.isRunning()) executionJobs[runtime.id]?.cancel()
            runtime.requestInterrupt()
        }

    private fun admit(runtime: CaseRuntime) {
        var refusal: String? = null
        try {
            // Publish the waiting turn before trying the lock: a completing preparation may resume
            // the family concurrently, and a short lock owner calls back once it is done.
            gate.withAdmission(runtime.id, onAvailable = { scope.launch { resumeIfPending(runtime.id) } }) {
                refusal = admitRun(runtime)
            }
        } catch (e: Exception) {
            // The message is already stored: a gate error must not become an HTTP failure.
            failAdmission(runtime, e)
        }
        refusal?.let { refuseAdmission(runtime, it) }
    }

    /**
     * Decide whether a deferred turn starts. Runs under the capability's lifecycle lock
     * ([CaseLaunchGate.withAdmission]) but outside the admission lock, since the decision reads the store.
     *
     * [LaunchDecision.Wait] leaves the turn in [deferredRuns]: a preparation resumes held turns once
     * its workspace is ready, and that resume must still find the turn. Holding the lifecycle lock
     * until the job is published keeps an admission valid until a cleanup can see it running. Only
     * taking the turn and publishing its launch need the admission lock, which keeps Stop and Kill
     * from falling between them. A runtime replaced since the turn was deferred starts nothing.
     *
     * Returns the reason of a [LaunchDecision.Refuse], reported by the caller outside both locks.
     */
    private fun admitRun(runtime: CaseRuntime): String? =
        when (val decision = gate.launchDecision(runtime.id)) {
            is LaunchDecision.Wait -> null
            is LaunchDecision.Refuse -> decision.reason
            is LaunchDecision.Admit -> {
                synchronized(runtime) {
                    // A worker completion and a lock callback can both try: only one consumes the turn.
                    if (isCurrent(runtime) && deferredRuns.remove(runtime.id)) startAdmittedJob(runtime)
                }
                null
            }
        }

    /** A Kill evicts the runtime: a turn deferred since then belongs to the runtime that replaced it. */
    private fun isCurrent(runtime: CaseRuntime): Boolean = runtimeOf(runtime.id) === runtime

    /**
     * Creates and starts the lazy coroutine for an admitted turn, under the admission lock.
     *
     * The job is published while the lifecycle lock is still held, so a cleanup that checks running
     * executions under that lock sees it before the runtime starts.
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
                scope.launch(start = CoroutineStart.LAZY) { runtime.run() }.also { admittedJob = it }
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
        dropTurn(
            runtime,
            "This message could not be started because its workspace could not be checked. Send it again.",
        )
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
        dropTurn(runtime, "This message could not be started because its workspace is unavailable: $reason")
    }

    /**
     * Take back a turn that will not start, then warn the user and return the case to `IDLE`.
     *
     * Taking the turn and claiming `IDLE` happen together under the admission lock, so only one
     * caller reports it, and nothing is claimed while an admitted launch is still active. The
     * warning and the status are persisted after the lock is released. `IDLE` is not published once
     * a newer turn has claimed the runtime or a Kill has already been saved.
     */
    private fun dropTurn(
        runtime: CaseRuntime,
        message: String,
    ) {
        val dropped =
            synchronized(runtime) {
                if (isCurrent(runtime) && deferredRuns.remove(runtime.id)) {
                    val launchActive = executionJobs[runtime.id]?.isActive == true
                    DroppedTurn(idle = if (launchActive) null else runtime.claimCancelPending())
                } else {
                    null
                }
            }
        // The message is already stored: failing to report the drop must not turn into an HTTP failure.
        dropped?.let {
            runCatching {
                runtime.emitEvent(
                    storeEvent(WarnEvent(namespaceId = runtime.namespaceId, caseId = runtime.id, message = message)),
                )
            }.onFailure { e -> logger.error(e) { "Could not warn case ${runtime.id} that its instruction was not started" } }
            it.idle
                ?.takeIf { runtime.statusFlow.value == CaseStatus.IDLE && statusOf(runtime.id)?.isTerminal() != true }
                ?.let { idle -> publishQuietly(runtime, idle) }
        }
    }

    /** Persist a claimed status; a store failure is logged rather than failing the caller's request. */
    private fun publishQuietly(
        runtime: CaseRuntime,
        status: CaseStatus,
    ) {
        runCatching { runtime.publishStatus(status) }
            .onFailure { e -> logger.error(e) { "Could not persist status $status for case ${runtime.id}" } }
    }

    /** A turn taken back by [dropTurn], with the `IDLE` transition it claimed, if any. */
    private data class DroppedTurn(
        val idle: CaseStatus?,
    )

    companion object : KLogging()
}
