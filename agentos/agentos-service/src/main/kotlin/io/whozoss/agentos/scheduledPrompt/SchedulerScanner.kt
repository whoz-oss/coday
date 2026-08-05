package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import kotlinx.coroutines.runBlocking
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import jakarta.annotation.PostConstruct
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Periodic scanner that discovers [ScheduledPrompt]s due for execution and claims them,
 * and a separate tick that consumes available [ScheduledPromptUserRun]s.
 *
 * ### Two-tick architecture
 *
 * **[tickClaim]** (Phase A, every [SchedulerProperties.tickIntervalMs]):
 * 1. Query [ScheduledPromptRepository.findDue] for all prompts whose `nextRunAt <= now`.
 * 2. For each prompt, call [claim] — which inserts a CLAIMED run and synchronously
 *    calls [ScheduledPromptExecutor.materialize] to create PENDING UserRuns.
 * 3. Always advance `nextRunAt` via [ScheduledPromptRepository.advanceNextRunAt].
 *
 * **[tickConsume]** (Phase B, every [SchedulerProperties.consumeIntervalMs]):
 * Calls [ScheduledPromptExecutor.consumeAvailable] via `runBlocking`, which suspends
 * until ALL UserRuns from ALL batches have finished executing. Spring `fixedDelay`
 * guarantees no overlap between consecutive consume ticks.
 *
 * Both ticks are fully blocking — Spring's `@Scheduled(fixedDelay)` ensures no tick
 * overlaps with its own successor. No fire-and-forget coroutines are used at the
 * Scanner level.
 *
 * ### Claim logic
 *
 * For a given [ScheduledPrompt]:
 * - If [ScheduledPromptRunRepository.hasActive] is true → insert a SKIPPED run (overlap).
 * - Otherwise → insert a CLAIMED run and synchronously materialize UserRuns.
 * - In all cases, attempt [ScheduledPromptRunRepository.insert]; if [DuplicateRunException]
 *   is thrown (concurrent tick won the race), log and continue.
 * - After the insert (or on [DuplicateRunException]), advance `nextRunAt` via CAS.
 *
 * The [clock] is injected so tests can freeze time.
 */
@Component
@ConditionalOnProperty(name = ["scheduler.enabled"], havingValue = "true")
class SchedulerScanner(
    private val scheduledPromptRepository: ScheduledPromptRepository,
    private val runRepository: ScheduledPromptRunRepository,
    private val agentConfigService: AgentConfigService,
    private val properties: SchedulerProperties,
    private val clock: Clock,
    private val nextRunCalculatorService: NextRunCalculatorService,
    private val executor: ScheduledPromptExecutor,
) {
    @PostConstruct
    fun logStartup() {
        logger.info {
            "[SchedulerScanner] Scheduler enabled — tickClaim every ${properties.tickIntervalMs}ms, " +
                "tickConsume every ${properties.consumeIntervalMs}ms"
        }
    }

    /**
     * Phase A: Discover due ScheduledPrompts, claim them, and materialize UserRuns.
     * Fully blocking — materialize runs synchronously within the tick.
     * Spring fixedDelay guarantees no overlap between ticks.
     */
    @Scheduled(fixedDelayString = "\${scheduler.tick-interval-ms:60000}")
    fun tickClaim() {
        val now = Instant.now(clock)
        scheduledPromptRepository.findDue(now)
            .also { due ->
                if (due.isEmpty()) logger.debug { "[SchedulerScanner] tickClaim: no due prompts" }
                else logger.info { "[SchedulerScanner] tickClaim: ${due.size} due prompt(s)" }
            }
            .forEach { sp -> claim(sp) }
    }

    /**
     * Phase B: Consume available UserRuns.
     * Fully blocking — returns only when all claimed UserRuns have finished executing.
     * Spring fixedDelay guarantees no overlap between ticks.
     */
    @Scheduled(fixedDelayString = "\${scheduler.consume-interval-ms:10000}")
    fun tickConsume() {
        runBlocking { executor.consumeAvailable() }
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
            scheduledPromptRepository.save(scheduledPrompt.copy(enabled = false))
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
            scheduledPromptRepository.save(scheduledPrompt.copy(enabled = false))
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
            logger.debug { "[SchedulerScanner] CAS miss for sp=${scheduledPrompt.id} (another tick advanced first)" }
        }

        // Check if the end condition will be reached after this run.
        // If so, disable the ScheduledPrompt — no future run should fire.
        checkEndConditionAfterAdvance(scheduledPrompt, nextSlot)

        if (status == RunStatus.CLAIMED && insertedRun != null) {
            executor.materialize(insertedRun, scheduledPrompt)
        }
    }

    /**
     * Check if the end condition is already reached BEFORE executing.
     *
     * - **ON_DATE**: the current slot (nextRunAt) falls after [Planning.endDate] at [Recurrence.timeUtc].
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
                    ?.atTime(scheduledPrompt.recurrence.timeUtc)
                    ?.toInstant(ZoneOffset.UTC)
                endInstant != null && scheduledPrompt.nextRunAt.isAfter(endInstant)
            }
            SchedulerEndType.OCCURRENCES -> {
                val max = planning.maxOccurrenceCount ?: return false
                val completed = runRepository.countCompletedRuns(scheduledPrompt.id)
                completed >= max
            }
        }
    }

    /**
     * Disable the [ScheduledPrompt] if the end condition will be reached after this run.
     *
     * Called after advancing nextRunAt. Checks the NEXT slot, not the current one.
     *
     * - **ON_DATE**: the next slot falls after [Planning.endDate] at [Recurrence.timeUtc].
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
                    ?.atTime(scheduledPrompt.recurrence.timeUtc)
                    ?.toInstant(ZoneOffset.UTC)
                endInstant != null && nextSlot.isAfter(endInstant)
            }
            SchedulerEndType.OCCURRENCES -> {
                val max = planning.maxOccurrenceCount ?: return
                val completed = runRepository.countCompletedRuns(scheduledPrompt.id)
                completed >= max
            }
        }

        if (shouldDisable) {
            logger.info {
                "[SchedulerScanner] End condition reached after advance for sp=${scheduledPrompt.id} " +
                    "(${planning.endType}) \u2014 disabling"
            }
            scheduledPromptRepository.save(scheduledPrompt.copy(enabled = false))
        }
    }

    companion object : KLogging()
}
