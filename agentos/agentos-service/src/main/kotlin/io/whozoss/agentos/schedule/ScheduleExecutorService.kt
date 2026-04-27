package io.whozoss.agentos.schedule

import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Polls due [Schedule]s and injects their message into the target case.
 *
 * Runs on a fixed-delay loop (default: 60 s, configurable via
 * `agentos.scheduler.poll-interval-seconds`). The component is disabled
 * entirely when `agentos.scheduler.enabled=false`.
 *
 * ### Execution flow (per due schedule)
 * 1. Resolve target case: use [Schedule.caseId] if set, otherwise create a
 *    new [Case] under the same namespace.
 * 2. Inject [Schedule.message] via [CaseService.addMessage] using the
 *    [SCHEDULER_ACTOR].
 * 3. Update tracking fields: [Schedule.lastTriggeredAt], [Schedule.occurrenceCount],
 *    [Schedule.nextTriggerAt] (computed by [ScheduleTriggerCalculator]).
 * 4. If the schedule is now expired ([ScheduleTriggerCalculator.isExpiredAfterTrigger]),
 *    soft-delete it.
 *
 * ### Catch-up
 * When the service restarts after downtime, multiple schedules may be due at
 * once. The [CatchUpPolicy] (configurable via `agentos.scheduler.catch-up-policy`,
 * default: [CatchUpPolicy.LAST]) controls which missed occurrences are replayed.
 * Today each schedule has a single [Schedule.nextTriggerAt] cursor so at most
 * one occurrence is due per schedule per poll; the policy becomes relevant when
 * a schedule with a very short interval has accumulated many missed ticks.
 *
 * ### Multi-instance safety
 * The current implementation is designed for single-instance deployments
 * (local / embedded). Distributed locking (e.g. ShedLock) should be added
 * before running multiple service replicas.
 */
@Service
@ConditionalOnProperty(name = ["agentos.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class ScheduleExecutorService(
    private val scheduleRepository: ScheduleRepository,
    private val caseService: CaseService,
    private val userService: UserService,
    private val config: SchedulerConfig,
) {

    @Scheduled(
        fixedDelayString = "\${agentos.scheduler.poll-interval-seconds:60}",
        timeUnit = TimeUnit.SECONDS,
    )
    fun poll() {
        val now = Instant.now()
        logger.debug { "[ScheduleExecutor] Poll at $now" }

        // Gather all namespaces that have at least one due schedule.
        // findDueSchedules returns enabled, non-removed schedules with
        // nextTriggerAt <= now, ordered by nextTriggerAt ASC.
        val due = scheduleRepository.findDueSchedules(now)
        if (due.isEmpty()) return

        logger.info { "[ScheduleExecutor] ${due.size} schedule(s) due" }

        // Apply catch-up policy: group by schedule id in case the same
        // schedule appears more than once (future-proofing), then filter.
        val toFire = due
            .groupBy { it.id }
            .values
            .flatMap { ScheduleTriggerCalculator.applyCatchUpPolicy(it, config.catchUpPolicy) }

        toFire.forEach { schedule ->
            try {
                fire(schedule, now)
            } catch (e: Exception) {
                logger.error(e) { "[ScheduleExecutor] Error firing schedule ${schedule.id}" }
            }
        }
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private fun fire(schedule: Schedule, now: Instant) {
        logger.info { "[ScheduleExecutor] Firing schedule ${schedule.id} (namespace ${schedule.namespaceId})" }

        val caseId = resolveOrCreateCase(schedule)
        val actor = resolveActor(schedule)

        caseService.addMessage(
            caseId = caseId,
            actor = actor,
            content = listOf(MessageContent.Text(schedule.message)),
        )

        val newOccurrenceCount = schedule.occurrenceCount + 1
        val expired = ScheduleTriggerCalculator.isExpiredAfterTrigger(schedule, newOccurrenceCount)
        val nextTriggerAt = if (expired) null
            else ScheduleTriggerCalculator.computeNextTriggerAt(schedule, now, newOccurrenceCount)

        val updated = schedule.copy(
            lastTriggeredAt = now,
            occurrenceCount = newOccurrenceCount,
            nextTriggerAt = nextTriggerAt,
        )

        when {
            expired -> {
                scheduleRepository.save(updated)
                scheduleRepository.delete(schedule.id)
                logger.info { "[ScheduleExecutor] Schedule ${schedule.id} expired, soft-deleted" }
            }
            nextTriggerAt == null -> {
                // End condition reached (e.g. EndTimestamp passed) but not flagged
                // as oneShot — disable gracefully.
                scheduleRepository.save(updated.copy(enabled = false))
                logger.info { "[ScheduleExecutor] Schedule ${schedule.id} end condition reached, disabled" }
            }
            else -> {
                scheduleRepository.save(updated)
                logger.debug { "[ScheduleExecutor] Schedule ${schedule.id} next trigger: $nextTriggerAt" }
            }
        }
    }

    /**
     * Resolves the [Actor] to use when injecting the schedule's message.
     *
     * When [Schedule.userId] is set, looks up the persisted [User] to build an
     * actor with the correct display name. Falls back to the synthetic scheduler
     * actor when no user is associated (e.g. schedules created before this field
     * was introduced, or system-level schedules).
     */
    private fun resolveActor(schedule: Schedule): Actor {
        val user = schedule.userId?.let { userService.findById(it) }
        return when {
            user != null -> Actor(
                id = user.id.toString(),
                displayName = listOfNotNull(user.firstname, user.lastname)
                    .joinToString(" ")
                    .ifBlank { user.externalId },
                role = ActorRole.USER,
            )
            else -> {
                logger.warn { "[ScheduleExecutor] Schedule ${schedule.id} has no userId, using synthetic scheduler actor" }
                Actor(
                    id = SCHEDULER_ACTOR_ID,
                    displayName = "Scheduler",
                    role = ActorRole.USER,
                )
            }
        }
    }

    /**
     * Returns the target [Case.id] for the schedule.
     * Uses [Schedule.caseId] when set; otherwise creates a fresh [Case] in the
     * same namespace and returns its id.
     */
    private fun resolveOrCreateCase(schedule: Schedule): UUID {
        if (schedule.caseId != null) return schedule.caseId
        val newCase = caseService.create(
            Case(
                metadata = EntityMetadata(),
                namespaceId = schedule.namespaceId,
            ),
        )
        logger.info { "[ScheduleExecutor] Created case ${newCase.id} for schedule ${schedule.id}" }
        return newCase.id
    }

    companion object : KLogging() {
        const val SCHEDULER_ACTOR_ID = "00000000-0000-0000-0000-000000000001"
    }
}
