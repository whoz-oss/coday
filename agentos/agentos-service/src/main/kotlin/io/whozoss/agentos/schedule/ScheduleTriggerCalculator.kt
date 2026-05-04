package io.whozoss.agentos.schedule

import io.whozoss.agentos.sdk.schedule.EndCondition
import io.whozoss.agentos.sdk.schedule.IntervalSchedule
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Pure, stateless calculator for [Schedule] temporal logic.
 *
 * All methods are free of I/O and Spring dependencies — they operate only on
 * domain values and [Instant], making them straightforward to unit-test.
 *
 * Interval format supported by [computeNextTriggerAt]:
 *   - `"30min"` — every N minutes (1–59)
 *   - `"6h"`   — every N hours   (1–24)
 *   - `"14d"`  — every N days    (1–31)
 *   - `"3M"`   — every N months  (1–12)
 */
object ScheduleTriggerCalculator {

    private val INTERVAL_REGEX = Regex("""^(\d+)(min|h|d|M)$""")

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Computes the first [nextTriggerAt] for a [Schedule] that has never fired.
     *
     * - For a one-shot [Schedule.triggerAt] schedule: returns [triggerAt] itself.
     * - For an [IntervalSchedule]: returns the first occurrence at or after [now]
     *   that satisfies the [IntervalSchedule.daysOfWeek] filter (if any).
     *
     * Returns `null` when the schedule is already expired (end condition already
     * met before [now]) or has neither strategy set.
     */
    fun computeInitialTriggerAt(schedule: Schedule, now: Instant): Instant? =
        when {
            schedule.triggerAt != null -> schedule.triggerAt
            schedule.intervalSchedule != null ->
                firstOccurrenceAtOrAfter(schedule.intervalSchedule, schedule.intervalSchedule.startTimestamp, now)
            else -> null
        }

    /**
     * Computes the next [nextTriggerAt] after a successful execution.
     *
     * For an [IntervalSchedule], advances from [lastTrigger] by one interval
     * (repeating until a [IntervalSchedule.daysOfWeek]-matching day is found),
     * then checks the [EndCondition]:
     * - [EndCondition.Occurrences]: returns `null` when [occurrenceCount] has
     *   reached the limit (the schedule should be disabled/deleted by the caller).
     * - [EndCondition.EndTimestamp]: returns `null` when the computed next
     *   trigger is past the deadline.
     *
     * For a one-shot [Schedule.triggerAt] schedule: always returns `null` (fired once).
     */
    fun computeNextTriggerAt(
        schedule: Schedule,
        lastTrigger: Instant,
        occurrenceCount: Int,
    ): Instant? {
        val intervalSchedule = schedule.intervalSchedule ?: return null

        val candidate = advanceByInterval(intervalSchedule, lastTrigger)
        val next = advanceToMatchingDay(intervalSchedule, candidate) ?: return null

        return when (val end = intervalSchedule.endCondition) {
            is EndCondition.Occurrences -> if (occurrenceCount >= end.count) null else next
            is EndCondition.EndTimestamp -> if (next.isAfter(end.timestamp)) null else next
            null -> next
        }
    }

    /**
     * Returns `true` when the schedule has reached its end condition and should
     * be soft-deleted or disabled after its next (or current) trigger.
     *
     * Always `true` for [Schedule.oneShot] schedules.
     * Always `true` for one-shot [Schedule.triggerAt] schedules.
     */
    fun isExpiredAfterTrigger(schedule: Schedule, occurrenceCount: Int): Boolean {
        if (schedule.oneShot) return true
        if (schedule.triggerAt != null) return true
        val end = schedule.intervalSchedule?.endCondition ?: return false
        return when (end) {
            is EndCondition.Occurrences -> occurrenceCount >= end.count
            is EndCondition.EndTimestamp -> true // checked in computeNextTriggerAt; if we reach here it's expired
        }
    }

    /**
     * Given a list of due [Schedule]s that all share the same [scheduleId] (i.e.
     * multiple missed occurrences), applies the [CatchUpPolicy] to decide which
     * subset should actually be triggered.
     *
     * In practice today each schedule appears at most once in the due list
     * (since [nextTriggerAt] is a single cursor), so this method is mainly
     * relevant when the executor accumulates skipped triggers. Reserved for
     * future use — currently the executor calls it with a single-element list.
     */
    fun applyCatchUpPolicy(
        dueSchedules: List<Schedule>,
        policy: CatchUpPolicy,
    ): List<Schedule> =
        when (policy) {
            CatchUpPolicy.ALL -> dueSchedules
            CatchUpPolicy.NONE -> emptyList()
            CatchUpPolicy.FIRST -> listOfNotNull(dueSchedules.minByOrNull { it.nextTriggerAt ?: Instant.MIN })
            CatchUpPolicy.LAST -> listOfNotNull(dueSchedules.maxByOrNull { it.nextTriggerAt ?: Instant.MIN })
        }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Returns the first occurrence of [intervalSchedule] that is at or after [from],
     * starting the search from [anchor] (which may be before [from]).
     */
    private fun firstOccurrenceAtOrAfter(
        intervalSchedule: IntervalSchedule,
        anchor: Instant,
        from: Instant,
    ): Instant? {
        var candidate = anchor
        // Advance until we are at or past `from`
        while (candidate.isBefore(from)) {
            candidate = advanceByInterval(intervalSchedule, candidate)
        }
        // Then apply day-of-week filter
        return advanceToMatchingDay(intervalSchedule, candidate)
    }

    /** Advances [from] by exactly one interval unit. */
    private fun advanceByInterval(intervalSchedule: IntervalSchedule, from: Instant): Instant {
        val match = INTERVAL_REGEX.matchEntire(intervalSchedule.interval)
            ?: error("Invalid interval format: '${intervalSchedule.interval}'")
        val amount = match.groupValues[1].toLong()
        val unit = match.groupValues[2]
        return when (unit) {
            "min" -> from.plus(amount, ChronoUnit.MINUTES)
            "h" -> from.plus(amount, ChronoUnit.HOURS)
            "d" -> from.plus(amount, ChronoUnit.DAYS)
            "M" -> {
                val ldt = from.atZone(ZoneOffset.UTC).toLocalDateTime()
                ldt.plusMonths(amount).toInstant(ZoneOffset.UTC)
            }
            else -> error("Unsupported interval unit: '$unit'")
        }
    }

    /**
     * If [intervalSchedule] has a [IntervalSchedule.daysOfWeek] filter, advances
     * [candidate] by one interval at a time until a matching day is found.
     * Returns `null` if no matching day is found within 7 advances (safety guard).
     * Returns [candidate] unchanged when no filter is set.
     */
    private fun advanceToMatchingDay(intervalSchedule: IntervalSchedule, candidate: Instant): Instant? {
        val allowedDays = intervalSchedule.daysOfWeek ?: return candidate
        if (allowedDays.isEmpty()) return candidate
        var current = candidate
        repeat(7) {
            val dow = current.atZone(ZoneOffset.UTC).dayOfWeek.value % 7 // ISO: Mon=1..Sun=7 → 0=Sun..6=Sat
            if (dow in allowedDays) return current
            current = advanceByInterval(intervalSchedule, current)
        }
        return null
    }
}
