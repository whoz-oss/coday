package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.agentConfig.AgentConfigService
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
    private val agentConfigService: AgentConfigService,
    private val properties: SchedulerProperties,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${scheduler.tick-interval-ms:60000}")
    fun tick() {
        val now = Instant.now(clock)
        scheduledPromptRepository.findDue(now)
            .also { due ->
                if (due.isEmpty()) logger.debug { "[SchedulerScanner] tick: no due prompts" }
                else logger.info { "[SchedulerScanner] tick: ${due.size} due prompt(s)" }
            }
            .forEach { sp -> claim(sp) }
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

        try {
            runRepository.insert(run)
            logger.info { "[SchedulerScanner] Inserted run correlationId=$correlationId status=$status" }
        } catch (e: DuplicateRunException) {
            logger.info { "[SchedulerScanner] Duplicate slot for sp=${scheduledPrompt.id} slot=$slot — another tick won the race" }
        }

        // Always advance nextRunAt — auto-repairing even on duplicate or skip.
        val nextSlot = NextRunCalculator.nextAfter(scheduledPrompt.recurrence, scheduledPrompt.planning, slot, clock)
        val advanced = scheduledPromptRepository.advance(scheduledPrompt.id, slot, nextSlot)
        if (advanced) {
            logger.debug { "[SchedulerScanner] Advanced sp=${scheduledPrompt.id} nextRunAt=$nextSlot" }
        } else {
            logger.debug { "[SchedulerScanner] CAS miss for sp=${scheduledPrompt.id} (another tick advanced first)" }
        }

        if (status == RunStatus.CLAIMED) {
            logger.info { "[SchedulerScanner] TODO execute sp=${scheduledPrompt.id} correlationId=$correlationId" }
        }
    }

    companion object : KLogging()
}
