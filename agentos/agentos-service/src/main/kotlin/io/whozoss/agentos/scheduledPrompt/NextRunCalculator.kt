package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
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
 * **DAY without `days` filter** (`days` is empty):
 * - Fires every `every` days starting from `startDate`.
 * - If the time on the candidate day has already passed, advance one full `every`-day period.
 *
 * **DAY with `days` filter**:
 * - Fires on any day that is in the `days` list, regardless of `every`.
 * - `every` is intentionally ignored when `days` is non-empty — the filter already constrains
 *   frequency and mixing the two would produce confusing results.
 *
 * **WEEK**:
 * - Fires every `every` weeks, always on the same day of the week as `startDate`.
 *
 * **MONTH**:
 * - Fires every `every` months, on the same day-of-month as `startDate`.
 * - If the target month is shorter than `startDate.dayOfMonth`, the last day of that month is used.
 *
 * The calculator is **pure** (no side effects) and takes an explicit [clock] so tests can inject
 * a fixed instant.
 */
object NextRunCalculator {

    /**
     * Compute the next run instant.
     *
     * @param sp the scheduled prompt whose [ScheduledPrompt.recurrence] and [ScheduledPrompt.planning] drive the calculation
     * @param clock source of "now"; defaults to UTC system clock
     * @return UTC instant of the next run
     */
    fun compute(sp: ScheduledPrompt, clock: Clock = Clock.systemUTC()): Instant {
        val now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC))
        val startDateTime = sp.planning.startDate.atTime(sp.recurrence.timeUtc)
        // Candidate: earliest point we are allowed to schedule from
        val candidate = if (startDateTime.isAfter(now)) startDateTime else now

        return when (sp.recurrence.unit) {
            SchedulerUnit.DAY -> computeDay(candidate, sp)
            SchedulerUnit.WEEK -> computeWeek(candidate, sp)
            SchedulerUnit.MONTH -> computeMonth(candidate, sp)
        }.toInstant(ZoneOffset.UTC)
    }

    // -------------------------------------------------------------------------
    // DAY
    // -------------------------------------------------------------------------

    private fun computeDay(candidate: LocalDateTime, sp: ScheduledPrompt): LocalDateTime {
        val timeUtc = sp.recurrence.timeUtc
        val startDate = sp.planning.startDate
        val every = sp.recurrence.every
        val days = sp.recurrence.days

        return when {
            days.isEmpty() -> computeDayEveryN(candidate, startDate, every, timeUtc)
            else -> computeDayWithFilter(candidate, startDate, days, timeUtc)
        }
    }

    /**
     * DAY without filter: fires every [every] days anchored on [startDate].
     *
     * We find the slot on [candidate]'s date; if it has already passed (or is in the past),
     * we advance by [every] days until we land at or after [candidate].
     */
    private fun computeDayEveryN(
        candidate: LocalDateTime,
        startDate: LocalDate,
        every: Int,
        timeUtc: java.time.LocalTime,
    ): LocalDateTime {
        // Anchor on startDate; step forward in multiples of `every` days.
        var slotDate = startDate
        val slotTime = timeUtc

        // Fast-forward to the first slot >= candidate.toLocalDate()
        while (slotDate.isBefore(candidate.toLocalDate())) {
            slotDate = slotDate.plusDays(every.toLong())
        }

        // If we land on the same day but the time has already passed, advance one more period.
        val slotDateTime = slotDate.atTime(slotTime)
        return if (!slotDateTime.isBefore(candidate)) slotDateTime
        else slotDate.plusDays(every.toLong()).atTime(slotTime)
    }

    /**
     * DAY with day-of-week filter: fires on any day in [days], starting from [startDate].
     * [every] is ignored (the filter already constrains frequency).
     */
    private fun computeDayWithFilter(
        candidate: LocalDateTime,
        startDate: LocalDate,
        days: List<DayOfWeek>,
        timeUtc: java.time.LocalTime,
    ): LocalDateTime {
        val sortedDays = days.map { it.value }.toSortedSet() // 1=MON..7=SUN
        // Start search from the later of startDate and candidate's date
        var searchDate = if (candidate.toLocalDate().isBefore(startDate)) startDate else candidate.toLocalDate()

        repeat(MAX_SEARCH_DAYS) {
            val dow = searchDate.dayOfWeek.value
            if (dow in sortedDays) {
                val slotDateTime = searchDate.atTime(timeUtc)
                if (!slotDateTime.isBefore(candidate)) return slotDateTime
            }
            searchDate = searchDate.plusDays(1)
        }
        // Fallback: should never happen with a non-empty days list and MAX_SEARCH_DAYS = 8
        return candidate.toLocalDate().plusDays(8).atTime(timeUtc)
    }

    // -------------------------------------------------------------------------
    // WEEK
    // -------------------------------------------------------------------------

    /**
     * WEEK: fires every [every] weeks, always on the same day-of-week as [startDate].
     */
    private fun computeWeek(candidate: LocalDateTime, sp: ScheduledPrompt): LocalDateTime {
        val timeUtc = sp.recurrence.timeUtc
        val startDate = sp.planning.startDate
        val every = sp.recurrence.every
        val targetDow = startDate.dayOfWeek

        // First occurrence >= startDate on the target day-of-week
        var slotDate = startDate.with(TemporalAdjusters.nextOrSame(targetDow))

        // Advance in steps of `every` weeks until >= candidate's date
        while (slotDate.isBefore(candidate.toLocalDate())) {
            slotDate = slotDate.plusWeeks(every.toLong())
        }

        val slotDateTime = slotDate.atTime(timeUtc)
        return if (!slotDateTime.isBefore(candidate)) slotDateTime
        else slotDate.plusWeeks(every.toLong()).atTime(timeUtc)
    }

    // -------------------------------------------------------------------------
    // MONTH
    // -------------------------------------------------------------------------

    /**
     * MONTH: fires every [every] months, on the same day-of-month as [startDate].
     * If the target month is shorter, clamps to the last day of that month.
     */
    private fun computeMonth(candidate: LocalDateTime, sp: ScheduledPrompt): LocalDateTime {
        val timeUtc = sp.recurrence.timeUtc
        val startDate = sp.planning.startDate
        val every = sp.recurrence.every
        val dayOfMonth = startDate.dayOfMonth

        // Build the first slot on startDate's month
        var slotDate = clampDayOfMonth(startDate.year, startDate.monthValue, dayOfMonth)

        // Advance in steps of `every` months until >= candidate's date
        while (slotDate.isBefore(candidate.toLocalDate())) {
            val next = slotDate.plusMonths(every.toLong())
            slotDate = clampDayOfMonth(next.year, next.monthValue, dayOfMonth)
        }

        val slotDateTime = slotDate.atTime(timeUtc)
        return if (!slotDateTime.isBefore(candidate)) slotDateTime
        else {
            val next = slotDate.plusMonths(every.toLong())
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
        // We want the first slot > after.  Build a synthetic candidate that is 1 second past
        // `after` (so the slot must be strictly after it), then delegate to the per-unit logic.
        val candidateLdt = LocalDateTime.ofInstant(after, ZoneOffset.UTC).plusSeconds(1)
        val startDateTime = planning.startDate.atTime(recurrence.timeUtc)
        val candidate = if (startDateTime.isAfter(candidateLdt)) startDateTime else candidateLdt

        val syntheticSp = object {
            val recurrenceVal = recurrence
            val planningVal = planning
        }
        // Delegate to the private per-unit functions via a minimal ScheduledPrompt-like parameter.
        // We reuse the private helpers by routing through a temporary ScheduledPrompt.
        val tempSp = ScheduledPrompt(
            agentConfigId = java.util.UUID.randomUUID(),
            promptTemplateId = java.util.UUID.randomUUID(),
            name = "__nextAfter",
            recurrence = recurrence,
            planning = planning,
            nextRunAt = Instant.EPOCH,
        )
        return when (recurrence.unit) {
            SchedulerUnit.DAY -> computeDay(candidate, tempSp)
            SchedulerUnit.WEEK -> computeWeek(candidate, tempSp)
            SchedulerUnit.MONTH -> computeMonth(candidate, tempSp)
        }.toInstant(ZoneOffset.UTC)
    }

    private const val MAX_SEARCH_DAYS = 8
}
