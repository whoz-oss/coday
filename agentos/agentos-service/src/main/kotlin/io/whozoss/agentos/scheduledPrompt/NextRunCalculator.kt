package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.time.LocalTime

/**
 * Calculates the next UTC [Instant] at which a [ScheduledPrompt] should fire.
 *
 * ### Rules
 *
 * The candidate start point is `max(now, startDate@timeUtc)` — the later of "right now" and
 * "the first possible slot as of startDate".  The calculator then finds the first slot at or
 * after that point that satisfies the recurrence pattern.
 *
 * **WEEK**:
 * - Fires every week on the same day-of-week as `startDate`.
 * - If [Recurrence.days] is non-empty, fires on those specific days of the week instead.
 *
 * **MONTH**:
 * - Fires every month on the same day-of-month as `startDate`.
 * - If the target month is shorter than `startDate.dayOfMonth`, the last day of that month is used.
 * - [Recurrence.days] is ignored for MONTH.
 *
 * The calculator is **pure** (no side effects) and takes an explicit [clock] so tests can inject
 * a fixed instant.
 */
object NextRunCalculator {

    /**
     * Compute the next run instant.
     *
     * @param scheduledPrompt the scheduled prompt whose [ScheduledPrompt.recurrence] and [ScheduledPrompt.planning] drive the calculation
     * @param clock source of "now"; defaults to UTC system clock
     * @return UTC instant of the next run
     */
    fun compute(scheduledPrompt: ScheduledPrompt, clock: Clock = Clock.systemUTC()): Instant {
        val now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC))
        val startDateTime = scheduledPrompt.planning.startDate.atTime(scheduledPrompt.recurrence.timeUtc)
        // Candidate: earliest point we are allowed to schedule from
        val candidate = if (startDateTime.isAfter(now)) startDateTime else now

        return when (scheduledPrompt.recurrence.unit) {
            SchedulerUnit.WEEK -> computeWeek(candidate, scheduledPrompt.recurrence, scheduledPrompt.planning)
            SchedulerUnit.MONTH -> computeMonth(candidate, scheduledPrompt.recurrence, scheduledPrompt.planning)
        }.toInstant(ZoneOffset.UTC)
    }

    // -------------------------------------------------------------------------
    // WEEK
    // -------------------------------------------------------------------------

    /**
     * WEEK: fires every week.
     *
     * If [recurrence.days] is non-empty, fires on those specific days of the week
     * (e.g. every Tuesday and Thursday). Otherwise fires on the same day-of-week as [startDate].
     */
    private fun computeWeek(candidate: LocalDateTime, recurrence: Recurrence, planning: Planning): LocalDateTime {
        val timeUtc = recurrence.timeUtc
        val startDate = planning.startDate
        val days = recurrence.days

        return if (days.isEmpty()) {
            computeWeekSameDay(candidate, startDate, timeUtc)
        } else {
            computeWeekWithDayFilter(candidate, startDate, days, timeUtc)
        }
    }

    /**
     * WEEK without day filter: fires every week on the same day-of-week as [startDate].
     */
    private fun computeWeekSameDay(
        candidate: LocalDateTime,
        startDate: LocalDate,
        timeUtc: LocalTime,
    ): LocalDateTime {
        val targetDow = startDate.dayOfWeek
        var slotDate = startDate.with(TemporalAdjusters.nextOrSame(targetDow))

        while (slotDate.isBefore(candidate.toLocalDate())) {
            slotDate = slotDate.plusWeeks(1)
        }

        val slotDateTime = slotDate.atTime(timeUtc)
        return if (!slotDateTime.isBefore(candidate)) slotDateTime
        else slotDate.plusWeeks(1).atTime(timeUtc)
    }

    /**
     * WEEK with day-of-week filter: fires every week on any day in [days].
     * Searches up to [MAX_SEARCH_DAYS] days ahead (at most 7 for a weekly filter).
     */
    private fun computeWeekWithDayFilter(
        candidate: LocalDateTime,
        startDate: LocalDate,
        days: List<DayOfWeek>,
        timeUtc: LocalTime,
    ): LocalDateTime {
        val sortedDays = days.map { it.value }.toSortedSet() // 1=MON..7=SUN
        var searchDate = if (candidate.toLocalDate().isBefore(startDate)) startDate else candidate.toLocalDate()

        repeat(MAX_SEARCH_DAYS) {
            if (searchDate.dayOfWeek.value in sortedDays) {
                val slotDateTime = searchDate.atTime(timeUtc)
                if (!slotDateTime.isBefore(candidate)) return slotDateTime
            }
            searchDate = searchDate.plusDays(1)
        }
        // Fallback: should never happen with a non-empty days list
        return candidate.toLocalDate().plusDays(8).atTime(timeUtc)
    }

    // -------------------------------------------------------------------------
    // MONTH
    // -------------------------------------------------------------------------

    /**
     * MONTH: fires every month on the same day-of-month as [startDate].
     * If the target month is shorter, clamps to the last day of that month.
     * [recurrence.days] is ignored for MONTH.
     */
    private fun computeMonth(candidate: LocalDateTime, recurrence: Recurrence, planning: Planning): LocalDateTime {
        val timeUtc = recurrence.timeUtc
        val startDate = planning.startDate
        val dayOfMonth = startDate.dayOfMonth

        var slotDate = clampDayOfMonth(startDate.year, startDate.monthValue, dayOfMonth)

        while (slotDate.isBefore(candidate.toLocalDate())) {
            val next = slotDate.plusMonths(1)
            slotDate = clampDayOfMonth(next.year, next.monthValue, dayOfMonth)
        }

        val slotDateTime = slotDate.atTime(timeUtc)
        return if (!slotDateTime.isBefore(candidate)) slotDateTime
        else {
            val next = slotDate.plusMonths(1)
            clampDayOfMonth(next.year, next.monthValue, dayOfMonth).atTime(timeUtc)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Build a [LocalDate] for the given year/month, clamping [day] to the last valid day. */
    private fun clampDayOfMonth(year: Int, month: Int, day: Int): LocalDate {
        val maxDay = java.time.YearMonth.of(year, month).lengthOfMonth()
        return LocalDate.of(year, month, minOf(day, maxDay))
    }

    /**
     * Compute the next slot that is strictly after [after].
     *
     * Unlike [compute] (which uses `max(now, startDate@timeUtc)` as its candidate),
     * [nextAfter] treats [after] as the lower bound directly — useful for advancing
     * [ScheduledPrompt.nextRunAt] after a run has been claimed.
     *
     * @param recurrence recurrence configuration
     * @param planning planning configuration (startDate, endType, ...)
     * @param after the instant after which the next slot must fall
     * @param clock source of "now" (used only to satisfy [compute]'s signature; the candidate
     *   is derived from [after], not from the clock)
     * @return UTC instant of the next slot strictly after [after]
     */
    fun nextAfter(recurrence: Recurrence, planning: Planning, after: Instant, clock: Clock = Clock.systemUTC()): Instant {
        // We want the first slot > after.  Build a candidate that is 1 second past `after`
        // (so the resulting slot must be strictly after it), then delegate to the per-unit logic.
        val candidateLdt = LocalDateTime.ofInstant(after, ZoneOffset.UTC).plusSeconds(1)
        val startDateTime = planning.startDate.atTime(recurrence.timeUtc)
        val candidate = if (startDateTime.isAfter(candidateLdt)) startDateTime else candidateLdt

        return when (recurrence.unit) {
            SchedulerUnit.WEEK -> computeWeek(candidate, recurrence, planning)
            SchedulerUnit.MONTH -> computeMonth(candidate, recurrence, planning)
        }.toInstant(ZoneOffset.UTC)
    }

    private const val MAX_SEARCH_DAYS = 8
}
