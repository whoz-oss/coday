package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import jakarta.annotation.PostConstruct
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Periodic scanner that discovers [ScheduledPrompt]s due for execution and claims them,
 * and a separate tick that consumes available [ScheduledPromptUserRun]s.
 *
 * ### Two-tick architecture
 *
 * **[tickClaim]** (Phase A, every `agentos.prompt.scheduler.tick-interval-ms`):
 * 1. Sweep orphaned CLAIMED runs via [recoverOrphanedClaimedRuns] — handles the crash
 *    window between Run insert and [ScheduledPromptExecutor.materialize] in [claim].
 * 2. Query [ScheduledPromptRepository.findDue] for all prompts whose `nextRunAt <= now`.
 * 3. For each prompt, call [claim] — which inserts a CLAIMED or SKIPPED Run and then
 *    calls [ScheduledPromptExecutor.materialize] to create PENDING UserRuns.
 * 4. Always advance `nextRunAt` via [ScheduledPromptRepository.advanceNextRunAt].
 *
 * **[tickConsume]** (Phase B, every `agentos.prompt.scheduler.consume-interval-ms`):
 * Declared `suspend` — Spring Framework 6.1+ natively supports suspend functions on
 * `@Scheduled` methods. Calls [ScheduledPromptExecutor.consumeAvailable] which suspends
 * until ALL UserRuns from ALL batches have finished executing. Spring `fixedDelay`
 * guarantees no overlap between consecutive consume ticks.
 *
 * Both ticks use Spring's `@Scheduled(fixedDelay)` to ensure no tick overlaps with its
 * own successor. No fire-and-forget coroutines are used at the Scanner level.
 *
 * ### Claim logic
 *
 * For a given [ScheduledPrompt]:
 * - If [ScheduledPromptRunRepository.hasActive] is true → insert a SKIPPED run (audit record).
 * - Otherwise → insert a CLAIMED Run, then call [ScheduledPromptExecutor.materialize]
 *   (MERGE + RUNNING transition in a single `@Transactional` boundary).
 * - Catch [DuplicateRunException] for both SKIPPED and CLAIMED (concurrent tick won the race).
 * - After the insert (or on duplicate), advance `nextRunAt` optimistically.
 *
 * ### Orphan recovery
 *
 * A CLAIMED Run older than [ORPHAN_THRESHOLD] is presumed orphaned (crash between insert
 * and materialize). [recoverOrphanedClaimedRuns] marks such runs FAILED at the start of
 * each tick, unblocking the [ScheduledPromptRunRepository.hasActive] overlap guard.
 *
 * A RUNNING Run whose UserRuns are all terminal but whose completion check was never
 * called is handled by [recoverOrphanedRunningRuns], also called at the start of each
 * tick. This covers the crash window between [ScheduledPromptUserRunRepository.markTerminal]
 * and [ScheduledPromptExecutor.checkCompletion].
 *
 * The [clock] is injected so tests can freeze time.
 */

@Component
@ConditionalOnProperty(name = ["agentos.prompt.scheduler.enabled"], havingValue = "true")
class SchedulerScanner(
    private val scheduledPromptRepository: ScheduledPromptRepository,
    private val runRepository: ScheduledPromptRunRepository,
    private val userRunRepository: ScheduledPromptUserRunRepository,
    private val agentConfigService: AgentConfigService,
    private val properties: SchedulerProperties,
    private val clock: Clock,
    private val nextRunCalculatorService: NextRunCalculatorService,
    private val executor: ScheduledPromptExecutor,
) {
    @PostConstruct
    fun logStartup() {
        logger.info { "[SchedulerScanner] Scheduler enabled" }
        val leaseSeconds = properties.leaseMinutes * 60
        require(leaseSeconds > properties.launchTimeoutSeconds) {
            "[SchedulerScanner] agentos.prompt.scheduler.lease-minutes (${properties.leaseMinutes}m = ${leaseSeconds}s) " +
                "must be greater than launch-timeout-seconds (${properties.launchTimeoutSeconds}s). " +
                "A shorter lease causes UserRuns to be reclaimed before monitorLaunch completes, " +
                "leading to double execution."
        }
    }

    /**
     * Phase A: Discover due ScheduledPrompts, claim them, and materialize UserRuns.
     * Fully blocking — materialize runs synchronously within the tick.
     * Spring fixedDelay guarantees no overlap between ticks.
     */
    @Scheduled(fixedDelayString = "\${agentos.prompt.scheduler.tick-interval-ms:30000}")
    fun tickClaim() {
        val now = Instant.now(clock)

        // Sweep: abandon orphaned CLAIMED runs that were never materialised.
        // This handles the crash window between Run insert and materialize() in claim().
        // A CLAIMED Run older than ORPHAN_THRESHOLD is presumed orphaned — materialize()
        // normally completes in seconds. Marking it FAILED unblocks the hasActive() overlap
        // guard so subsequent slots are not stuck in SKIPPED.
        runCatching { recoverOrphanedClaimedRuns(now) }.onFailure { e ->
            logger.error(e) { "[SchedulerScanner] recoverOrphanedClaimedRuns failed — continuing tick" }
        }

        // Sweep: close RUNNING runs whose UserRuns are all settled but whose completion
        // check was missed. This handles the crash window between markTerminal() and
        // checkCompletion() in ScheduledPromptExecutor.
        runCatching { recoverOrphanedRunningRuns(now) }.onFailure { e ->
            logger.error(e) { "[SchedulerScanner] recoverOrphanedRunningRuns failed — continuing tick" }
        }

        scheduledPromptRepository.findDue(now)
            .also { due ->
                if (due.isEmpty()) logger.debug { "[SchedulerScanner] tickClaim: no due prompts" }
                else logger.info { "[SchedulerScanner] tickClaim: ${due.size} due prompt(s)" }
            }
            .forEach { sp ->
                runCatching { claim(sp) }.onFailure { e ->
                    logger.error(e) { "[SchedulerScanner] claim failed for sp=${sp.id} — skipping this prompt in this tick" }
                }
            }
    }

    /**
     * Mark orphaned CLAIMED runs as FAILED.
     *
     * A Run stays CLAIMED only during the brief window between [ScheduledPromptRunRepository.insert]
     * and [ScheduledPromptExecutor.materialize] in [claim]. If the instance crashes in that window,
     * the Run remains CLAIMED forever — no UserRuns were created, the slot is lost.
     *
     * This sweep detects CLAIMED Runs older than [ORPHAN_THRESHOLD] and marks them FAILED with
     * an explanatory error. This:
     * - Unblocks [ScheduledPromptRunRepository.hasActive] so subsequent slots are not stuck in SKIPPED
     * - Leaves a diagnostic trace in the Run's error field
     * - Is safe in multi-instance: multiple instances may race to update the same orphan,
     *   but updateStatus is idempotent (second call is a no-op)
     */
    private fun recoverOrphanedClaimedRuns(now: Instant) {
        val threshold = now.minus(ORPHAN_THRESHOLD)
        val orphans = runRepository.findOrphanedClaimed(threshold)
        orphans.forEach { run ->
            logger.warn {
                "[SchedulerScanner] Orphaned CLAIMED run=${run.id} sp=${run.scheduledPromptId} " +
                    "created=${run.metadata.created} — marking FAILED (crash recovery)"
            }
            runRepository.updateStatus(
                id = run.id,
                status = RunStatus.FAILED,
                finishedAt = now,
                error = "Orphaned CLAIMED — materialize never completed (crash recovery)",
            )
        }
    }

    /**
     * Close RUNNING Runs whose UserRuns are all settled but whose completion check was missed.
     *
     * This handles the crash window where the last [ScheduledPromptUserRunRepository.markTerminal]
     * completed but the instance crashed before [ScheduledPromptExecutor]'s `checkCompletion()`
     * could transition the parent Run. Without this sweep, the Run would stay RUNNING forever
     * — no subsequent UserRun closure will re-trigger the check.
     *
     * Uses a single [ScheduledPromptRunRepository.findSettledRunning] query that filters
     * directly in the database: only RUNNING Runs with no UserRun in PENDING or RUNNING
     * status are returned. In normal operation this returns an empty list (no N+1 queries).
     * Only in crash recovery scenarios are results expected.
     *
     * Safe in multi-instance: [ScheduledPromptRunRepository.updateStatus] is idempotent
     * (second call is a no-op when the Run is already in the target status).
     */
    private fun recoverOrphanedRunningRuns(now: Instant) {
        val settledRuns = runRepository.findSettledRunning()
        for (run in settledRuns) {
            val finalStatus = when {
                userRunRepository.hasAnyFailed(run.id) -> RunStatus.FAILED
                else -> RunStatus.DONE
            }
            runRepository.updateStatus(run.id, finalStatus, now)
            logger.warn {
                "[SchedulerScanner] Orphaned RUNNING run=${run.id} sp=${run.scheduledPromptId} " +
                    "— all UserRuns settled, closing as $finalStatus (crash recovery)"
            }
        }
    }

    /**
     * Phase B: Consume available UserRuns.
     * Declared `suspend` — Spring Framework 6.1+ natively dispatches suspend `@Scheduled`
     * methods on the application's coroutine scheduler.
     * Returns only when all claimed UserRuns have finished executing.
     * Spring fixedDelay guarantees no overlap between ticks.
     *
     * The IO dispatcher is managed by [ScheduledPromptExecutor.consumeAvailable] itself
     * via [kotlinx.coroutines.withContext].
     */
    @Scheduled(fixedDelayString = "\${agentos.prompt.scheduler.consume-interval-ms:10000}")
    suspend fun tickConsume() {
        executor.consumeAvailable()
    }

    private fun claim(scheduledPrompt: ScheduledPrompt) {
        val slot = scheduledPrompt.nextRunAt
        val correlationId = "sp-${scheduledPrompt.id.toString().take(8)}-${slot.epochSecond}"

        // Guard: disable the ScheduledPrompt if its AgentConfig is gone or disabled.
        val agentConfig = agentConfigService.findById(scheduledPrompt.agentConfigId)
        if (agentConfig == null || !agentConfig.enabled) {
            logger.warn {
                "[SchedulerScanner] AgentConfig ${scheduledPrompt.agentConfigId} is ${if (agentConfig == null) "deleted" else "disabled"} " +
                    "— disabling sp=${scheduledPrompt.id} to prevent further zombie ticks"
            }
            scheduledPromptRepository.updateEnabled(scheduledPrompt.id, false)
            return
        }

        // Guard: disable if the end condition is already reached before executing.
        // This is a crash-recovery safety net: if the server was down while the end condition
        // expired, or if a previous tick crashed between advanceNextRunAt and the post-advance
        // disable, the ScheduledPrompt may still be enabled with nextRunAt past the endDate
        // (or with completed runs already at maxOccurrenceCount). Without this guard, each
        // tick would keep creating SKIPPED or CLAIMED runs indefinitely.
        // In multi-instance deployments, only one instance needs to win the disable — the
        // others will see enabled=false on their next findDue() and skip naturally.
        if (isEndConditionReached(scheduledPrompt)) {
            logger.info {
                "[SchedulerScanner] End condition already reached for sp=${scheduledPrompt.id} " +
                    "(${scheduledPrompt.planning.endType}) \u2014 disabling without execution"
            }
            scheduledPromptRepository.updateEnabled(scheduledPrompt.id, false)
            return
        }

        val status = when {
            runRepository.hasActive(scheduledPrompt.id) -> {
                logger.warn { "[SchedulerScanner] OVERLAP sp=${scheduledPrompt.id} has active run → SKIPPED" }
                RunStatus.SKIPPED
            }
            else -> RunStatus.CLAIMED
        }

        val run = ScheduledPromptRun(
            scheduledPromptId = scheduledPrompt.id,
            scheduledFor = slot,
            status = status,
            correlationId = correlationId,
        )

        val insertedRun = try {
            runRepository.insert(run)
                .also { logger.info { "[SchedulerScanner] Inserted run correlationId=$correlationId status=$status" } }
        } catch (e: DuplicateRunException) {
            logger.info { "[SchedulerScanner] Duplicate slot for sp=${scheduledPrompt.id} slot=$slot — another tick won the race" }
            null
        }

        // Always advance nextRunAt — auto-repairing even on duplicate or skip.
        val nextSlot = nextRunCalculatorService.nextAfter(recurrence = scheduledPrompt.recurrence, planning = scheduledPrompt.planning, after = slot)
        val advanced = scheduledPromptRepository.advanceNextRunAt(scheduledPrompt.id, slot, nextSlot)
        if (advanced) {
            logger.debug { "[SchedulerScanner] Advanced sp=${scheduledPrompt.id} nextRunAt=$nextSlot" }
        } else {
            logger.debug { "[SchedulerScanner] Optimistic advance miss for sp=${scheduledPrompt.id} (another tick advanced first)" }
        }

        // Check if the end condition will be reached after this run.
        // If so, disable the ScheduledPrompt — no future run should fire.
        checkEndConditionAfterAdvance(scheduledPrompt, nextSlot)

        if (status == RunStatus.CLAIMED && insertedRun != null) {
            runCatching { executor.materialize(insertedRun, scheduledPrompt) }
                .onFailure { e ->
                    logger.error(e) {
                        "[SchedulerScanner] materialize failed for run=${insertedRun.id} sp=${scheduledPrompt.id} — marking FAILED"
                    }
                    runCatching {
                        runRepository.updateStatus(
                            id = insertedRun.id,
                            status = RunStatus.FAILED,
                            finishedAt = Instant.now(clock),
                            error = e.message?.takeIf { it.isNotBlank() } ?: "materialize failed: ${e::class.simpleName}",
                        )
                    }.onFailure { updateError ->
                        logger.error(updateError) {
                            "[SchedulerScanner] Could not mark run=${insertedRun.id} as FAILED — will be swept by recoverOrphanedClaimedRuns"
                        }
                    }
                }
        }
    }

    /**
     * Check if the end condition is already reached BEFORE executing.
     *
     * - **ON_DATE**: the current slot (nextRunAt) falls on or after [Planning.endDate] at 00:00 UTC (exclusive — the prompt does not run from endDate onwards).
     * - **OCCURRENCES**: the number of completed (non-SKIPPED) runs has already reached
     *   [Planning.maxOccurrenceCount].
     * - **NEVER**: never reached.
     */
    private fun isEndConditionReached(scheduledPrompt: ScheduledPrompt): Boolean {
        val planning = scheduledPrompt.planning
        return when (planning.endType) {
            SchedulerEndType.NEVER -> false
            SchedulerEndType.ON_DATE -> {
                val endInstant = planning.endDate
                    ?.atStartOfDay()
                    ?.toInstant(ZoneOffset.UTC)
                endInstant != null && !scheduledPrompt.nextRunAt.isBefore(endInstant)
            }
            SchedulerEndType.OCCURRENCES -> {
                val max = planning.maxOccurrenceCount ?: return false
                val startInstant = planning.startDate.atStartOfDay(ZoneOffset.UTC).toInstant()
                val completed = runRepository.countCompletedRuns(scheduledPrompt.id, startInstant)
                completed >= max
            }
        }
    }

    /**
     * Disable the [ScheduledPrompt] if the end condition will be reached after this run.
     *
     * Called after advancing nextRunAt. Checks the NEXT slot, not the current one.
     *
     * - **ON_DATE**: the next slot falls on or after [Planning.endDate] at 00:00 UTC (exclusive — the prompt does not run from endDate onwards).
     * - **OCCURRENCES**: the number of completed (non-SKIPPED) runs (including the one just
     *   inserted) has reached [Planning.maxOccurrenceCount].
     * - **NEVER**: no end condition, never disables.
     */
    private fun checkEndConditionAfterAdvance(scheduledPrompt: ScheduledPrompt, nextSlot: Instant) {
        val planning = scheduledPrompt.planning
        val shouldDisable = when (planning.endType) {
            SchedulerEndType.NEVER -> false
            SchedulerEndType.ON_DATE -> {
                val endInstant = planning.endDate
                    ?.atStartOfDay()
                    ?.toInstant(ZoneOffset.UTC)
                endInstant != null && !nextSlot.isBefore(endInstant)
            }
            SchedulerEndType.OCCURRENCES -> {
                val max = planning.maxOccurrenceCount ?: return
                val startInstant = planning.startDate.atStartOfDay(ZoneOffset.UTC).toInstant()
                val completed = runRepository.countCompletedRuns(scheduledPrompt.id, startInstant)
                completed >= max
            }
        }

        if (shouldDisable) {
            logger.info {
                "[SchedulerScanner] End condition reached after advance for sp=${scheduledPrompt.id} " +
                    "(${planning.endType}) \u2014 disabling"
            }
            // Use targeted updateEnabled rather than save(copy(enabled=false)) to avoid
            // overwriting the nextRunAt that was just advanced above.
            scheduledPromptRepository.updateEnabled(scheduledPrompt.id, false)
        }
    }

    companion object : KLogging() {
        /** CLAIMED Runs older than this are presumed orphaned (crash between insert and materialize). */
        private val ORPHAN_THRESHOLD = Duration.ofMinutes(5)
    }
}
