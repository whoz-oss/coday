package io.whozoss.agentos.scheduledPrompt

import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * Periodic scanner that discovers [ScheduledPrompt]s due for execution and claims them.
 *
 * ### Tick logic
 *
 * Every [SchedulerProperties.tickIntervalMs] milliseconds:
 * 1. Query [ScheduledPromptRepository.findDue] for all prompts whose `nextRunAt <= now`.
 * 2. For each prompt, call [claim].
 * 3. Always advance `nextRunAt` via [ScheduledPromptRepository.advance] after claiming — this is
 *    the self-healing mechanism that prevents a stuck prompt from blocking the queue on every tick.
 *
 * ### Claim logic
 *
 * For a given [ScheduledPrompt]:
 * - If [ScheduledPromptRunRepository.hasActive] is true → insert a SKIPPED run (overlap).
 * - Otherwise → insert a CLAIMED run.
 * - In all cases, attempt [ScheduledPromptRunRepository.insert]; if [DuplicateRunException] is
 *   thrown (concurrent tick won the race), log and continue — the advance still happens.
 * - After the insert (or on [DuplicateRunException]), advance `nextRunAt` via CAS.
 * - If the run was CLAIMED: log “TODO execute” (no-op for now).
 *
 * The [clock] is injected so tests can freeze time.
 */
@Component
@ConditionalOnProperty(name = ["scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class SchedulerScanner(
    private val scheduledPromptRepository: ScheduledPromptRepository,
    private val runRepository: ScheduledPromptRunRepository,
    private val properties: SchedulerProperties,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${scheduler.tick-interval-ms:60000}")
    fun tick() {
        val now = Instant.now(clock)
        val due = scheduledPromptRepository.findDue(now)
        if (due.isEmpty()) {
            logger.debug { "[SchedulerScanner] tick: no due prompts" }
            return
        }
        logger.info { "[SchedulerScanner] tick: ${due.size} due prompt(s)" }
        due.forEach { sp -> claim(sp) }
    }

    private fun claim(sp: ScheduledPrompt) {
        val slot = sp.nextRunAt
        val correlationId = "sp-${sp.id.toString().take(8)}-${slot.epochSecond}"

        val status = when {
            runRepository.hasActive(sp.id) -> {
                logger.warn { "[SchedulerScanner] OVERLAP sp=${sp.id} has active run → SKIPPED" }
                RunStatus.SKIPPED
            }
            else -> RunStatus.CLAIMED
        }

        val run = ScheduledPromptRun(
            scheduledPromptId = sp.id,
            scheduledFor = slot,
            status = status,
            correlationId = correlationId,
        )

        try {
            runRepository.insert(run)
            logger.info { "[SchedulerScanner] Inserted run correlationId=$correlationId status=$status" }
        } catch (e: DuplicateRunException) {
            logger.info { "[SchedulerScanner] Duplicate slot for sp=${sp.id} slot=$slot — another tick won the race" }
        }

        // Always advance nextRunAt — auto-repairing even on duplicate or skip.
        val nextSlot = NextRunCalculator.nextAfter(sp.recurrence, sp.planning, slot, clock)
        val advanced = scheduledPromptRepository.advance(sp.id, slot, nextSlot)
        if (advanced) {
            logger.debug { "[SchedulerScanner] Advanced sp=${sp.id} nextRunAt=$nextSlot" }
        } else {
            logger.debug { "[SchedulerScanner] CAS miss for sp=${sp.id} (another tick advanced first)" }
        }

        if (status == RunStatus.CLAIMED) {
            logger.info { "[SchedulerScanner] TODO execute sp=${sp.id} correlationId=$correlationId" }
        }
    }

    companion object : KLogging()
}
