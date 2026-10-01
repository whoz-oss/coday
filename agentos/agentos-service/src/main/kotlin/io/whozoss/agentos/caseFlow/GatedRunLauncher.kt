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

    private fun admitRun(runtime: CaseRuntime): Unit =
        synchronized(runtime) {
            // A worker completion and a lock callback can both try: only one consumes the turn.
            if (!deferredRuns.remove(runtime.id)) return@synchronized
            if (!gate.canLaunch(runtime.id)) {
                deferredRuns.add(runtime.id)
                return@synchronized
            }
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
                        val allowed =
                            try {
                                // Preparation or cleanup may have started since the admission.
                                gate.canLaunch(runtime.id)
                            } catch (e: Exception) {
                                failAdmission(runtime, e)
                                return@launch
                            }
                        if (allowed) runtime.run() else deferredRuns.add(runtime.id)
                    }.also { admittedJob = it }
                }
            }
            finishingJob?.invokeOnCompletion { scope.launch { resumeIfPending(runtime.id) } }
            admittedJob?.let { job ->
                job.invokeOnCompletion { executionJobs.remove(runtime.id, job) }
                job.start()
            }
        }

    /** The gate could not decide: never start the run, tell the user and return to IDLE. */
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

    companion object : KLogging()
}
