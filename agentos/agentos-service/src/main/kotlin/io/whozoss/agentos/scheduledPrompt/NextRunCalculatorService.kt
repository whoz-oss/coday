package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

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
 * The calculator is **pure** (no side effects beyond reading [clock]). [clock] is injected as a
 * Spring bean (see `ClockConfiguration`) so production code gets `Clock.systemUTC()` while tests
 * can substitute a fixed [Clock] without needing a Spring context — this class can be
 * instantiated directly with `NextRunCalculatorService(fixedClock)`.
 */
@Service
class NextRunCalculatorService(
    private val clock: Clock,
) {

    /**
     * Compute the next run instant.
     *
     * @param scheduledPrompt the scheduled prompt whose [ScheduledPrompt.recurrence] and [ScheduledPrompt.planning] drive the calculation
     * @return UTC instant of the next run
     */
    fun compute(scheduledPrompt: ScheduledPrompt): Instant {
        val now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC))
        val startDateTime = scheduledPrompt.planning.startDate.atTime(scheduledPrompt.recurrence.timeUtc)
        // Candidate: earliest point we are allowed to schedule from
        val candidate = if (startDateTime.isAfter(now)) startDateTime else now

        return when (scheduledPrompt.recurrence.unit) {
            SchedulerUnit.WEEK -> computeWeek(candidate = candidate, recurrence = scheduledPrompt.recurrence, planning = scheduledPrompt.planning)
            SchedulerUnit.MONTH -> computeMonth(candidate = candidate, recurrence = scheduledPrompt.recurrence, planning = scheduledPrompt.planning)
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
            computeWeekSameDay(candidate = candidate, startDate = startDate, timeUtc = timeUtc)
        } else {
            computeWeekWithDayFilter(candidate = candidate, startDate = startDate, days = days, timeUtc = timeUtc)
        }
    }

    /**
     * WEEK without day filter: fires every week on the same day-of-week as [startDate].
     *
     * Jumps directly to the target week via [ChronoUnit.WEEKS] instead of looping one week at a
     * time — the gap between [startDate] and [candidate] is unbounded (a long-inactive
     * ScheduledPrompt could be months or years behind). `WEEKS.between` truncates toward zero
     * (integer division of the day gap by 7), so at most one extra week may be needed; the
     * following `isBefore` check applies that single correction.
     */
    private fun computeWeekSameDay(
        candidate: LocalDateTime,
        startDate: LocalDate,
        timeUtc: LocalTime,
    ): LocalDateTime {
        val targetDayOfWeek = startDate.dayOfWeek
        val candidateDate = candidate.toLocalDate()
        val firstSlot = startDate.with(TemporalAdjusters.nextOrSame(targetDayOfWeek))

        val weeksBetween = ChronoUnit.WEEKS.between(firstSlot, candidateDate)
        var slotDate = if (weeksBetween > 0) firstSlot.plusWeeks(weeksBetween) else firstSlot
        if (slotDate.isBefore(candidateDate)) slotDate = slotDate.plusWeeks(1)

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
        return candidate.toLocalDate().plusDays(MAX_SEARCH_DAYS.toLong()).atTime(timeUtc)
    }

    // -------------------------------------------------------------------------
    // MONTH
    // -------------------------------------------------------------------------

    /**
     * MONTH: fires every month on the same day-of-month as [startDate].
     * If the target month is shorter, clamps to the last day of that month.
     * [recurrence.days] is ignored for MONTH.
     *
     * Jumps directly to the target [YearMonth] via [ChronoUnit.MONTHS] instead of looping one
     * month at a time. [YearMonth.between] is exact (no day component, so no truncation), but the
     * clamped day within the landed month can still fall short of [candidate] when [dayOfMonth]
     * is small (e.g. `startDate` on the 5th, `candidate` on the 20th of the same target month) —
     * the `isBefore` check below applies that single correction.
     */
    private fun computeMonth(candidate: LocalDateTime, recurrence: Recurrence, planning: Planning): LocalDateTime {
        val timeUtc = recurrence.timeUtc
        val startDate = planning.startDate
        val dayOfMonth = startDate.dayOfMonth
        val candidateDate = candidate.toLocalDate()

        val firstYearMonth = YearMonth.from(startDate)
        val candidateYearMonth = YearMonth.from(candidateDate)
        val monthsBetween = ChronoUnit.MONTHS.between(firstYearMonth, candidateYearMonth)

        var targetYearMonth = if (monthsBetween > 0) firstYearMonth.plusMonths(monthsBetween) else firstYearMonth
        var slotDate = clampDayOfMonth(year = targetYearMonth.year, month = targetYearMonth.monthValue, day = dayOfMonth)
        if (slotDate.isBefore(candidateDate)) {
            targetYearMonth = targetYearMonth.plusMonths(1)
            slotDate = clampDayOfMonth(year = targetYearMonth.year, month = targetYearMonth.monthValue, day = dayOfMonth)
        }

        val slotDateTime = slotDate.atTime(timeUtc)
        return if (!slotDateTime.isBefore(candidate)) slotDateTime
        else {
            targetYearMonth = targetYearMonth.plusMonths(1)
            clampDayOfMonth(year = targetYearMonth.year, month = targetYearMonth.monthValue, day = dayOfMonth).atTime(timeUtc)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Build a [LocalDate] for the given year/month, clamping [day] to the last valid day. */
    private fun clampDayOfMonth(year: Int, month: Int, day: Int): LocalDate {
        val maxDay = YearMonth.of(year, month).lengthOfMonth()
        return LocalDate.of(year, month, minOf(day, maxDay))
    }

    /**
     * Compute the next slot that is strictly after [after].
     *
     * Unlike [compute] (which uses `max(now, startDate@timeUtc)` as its candidate),
     * [nextAfter] treats [after] as the lower bound directly — useful for advancing
     * [ScheduledPrompt.nextRunAt] after a run has been claimed. It does not read [clock]:
     * the candidate is derived entirely from [after].
     *
     * @param recurrence recurrence configuration
     * @param planning planning configuration (startDate, endType, ...)
     * @param after the instant after which the next slot must fall
     * @return UTC instant of the next slot strictly after [after]
     */
    fun nextAfter(recurrence: Recurrence, planning: Planning, after: Instant): Instant {
        // We want the first slot > after.  Build a candidate that is 1 second past `after`
        // (so the resulting slot must be strictly after it), then delegate to the per-unit logic.
        val candidateLdt = LocalDateTime.ofInstant(after, ZoneOffset.UTC).plusSeconds(1)
        val startDateTime = planning.startDate.atTime(recurrence.timeUtc)
        val candidate = if (startDateTime.isAfter(candidateLdt)) startDateTime else candidateLdt

        return when (recurrence.unit) {
            SchedulerUnit.WEEK -> computeWeek(candidate = candidate, recurrence = recurrence, planning = planning)
            SchedulerUnit.MONTH -> computeMonth(candidate = candidate, recurrence = recurrence, planning = planning)
        }.toInstant(ZoneOffset.UTC)
    }

    companion object {
        private const val MAX_SEARCH_DAYS = 8
    }
}
