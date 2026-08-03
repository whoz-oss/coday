package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.agentConfig.AgentConfigService
import kotlinx.coroutines.runBlocking
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import jakarta.annotation.PostConstruct
import java.time.Clock
import java.time.Instant

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

        if (status == RunStatus.CLAIMED && insertedRun != null) {
            executor.materialize(insertedRun, scheduledPrompt)
        }
    }

    companion object : KLogging()
}
