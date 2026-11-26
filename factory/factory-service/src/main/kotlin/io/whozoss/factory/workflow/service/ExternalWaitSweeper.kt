package io.whozoss.factory.workflow.service

import io.whozoss.factory.agentattempt.service.OutboxDrainService
import io.whozoss.factory.workflow.config.ExternalPollingProperties
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import java.time.Clock
import java.time.Instant
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Safety net of the steps parked in `waiting_external`.
 *
 * Parking a step and requesting its delayed wake commit together, so a wake is normally never lost. This sweeper
 * covers what a transaction cannot: a continuation that died through its retry budget, was dispatched without
 * re-arming, or is due later than the step expects. It only READS the parked steps and the continuation of each,
 * and re-arms an overdue one with an immediate wake; the sequencer then polls it, or fails it with
 * `POLL_TIMEOUT_EXCEEDED` once its 30-minute deadline passed. A parked step therefore always ends by its deadline
 * plus the sweep interval plus the grace.
 *
 * Registered under the same condition as [io.whozoss.factory.agentattempt.service.OutboxDrainWorker]: without the
 * drain worker nothing would consume the re-armed continuation.
 */
@Component
@ConditionalOnProperty(prefix = "factory.outbox", name = ["drain-enabled"], havingValue = "true")
class ExternalWaitSweeper(
    private val repository: WorkflowRepository,
    private val drain: OutboxDrainService,
    private val properties: ExternalPollingProperties = ExternalPollingProperties(),
    private val clock: Clock = Clock.systemUTC(),
) {

    private val logger = KotlinLogging.logger {}

    @Scheduled(
        fixedDelayString = "\${factory.polling.sweep-interval-ms:60000}",
        initialDelayString = "\${factory.polling.sweep-initial-delay-ms:60000}",
    )
    fun scheduledSweep() {
        runCatching { sweep() }.onFailure { logger.warn(it) { "External wait sweep failed" } }
    }

    /** One sweep; returns the number of parked steps whose wake had to be re-armed. */
    fun sweep(limit: Int = SWEEP_LIMIT): Int {
        val now = clock.instant()
        var rearmed = 0
        for (parked in repository.findStepStatesByStatus(WorkflowStatuses.WAITING_EXTERNAL, limit)) {
            val state = parked.state
            val poll = state.payload["poll"] as? Map<*, *>
            val due = (poll?.get("nextPollAt") as? String ?: poll?.get("deadlineAt") as? String)
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            // A parked step without a readable poll state is the worst case: treat it as overdue right away.
            if (due != null && now.isBefore(due.plusSeconds(properties.sweepGraceSeconds.toLong()))) continue
            val continuation = drain.continuationSnapshot(parked.scope, state.namespaceId, state.workflowId)
            if (continuation != null && isCovered(continuation.status, continuation.nextAttemptAt, due)) continue
            runCatching { drain.rearmExternalWait(parked.scope, state.namespaceId, state.workflowId, now) }
                .onSuccess {
                    rearmed++
                    logger.warn {
                        "Lost external wake re-armed: workflow '${state.workflowId}' step '${state.stepId}' " +
                            "(continuation ${continuation?.status ?: "absent"}, due ${due ?: "unknown"})"
                    }
                }
                .onFailure {
                    logger.error(it) { "Could not re-arm the wake of workflow '${state.workflowId}' step '${state.stepId}'" }
                }
        }
        return rearmed
    }

    /**
     * A continuation that is claimed (a worker runs it) or pending and due no later than the step expects will
     * poll the step by itself. Anything else (dispatched, dead, or due too late) leaves the step stranded.
     */
    private fun isCovered(status: String, nextAttemptAt: Instant?, stepDue: Instant?): Boolean = when (status) {
        "claimed" -> true
        "pending" -> nextAttemptAt == null || stepDue == null || !nextAttemptAt.isAfter(stepDue)
        else -> false
    }

    private companion object {
        const val SWEEP_LIMIT = 200
    }
}
