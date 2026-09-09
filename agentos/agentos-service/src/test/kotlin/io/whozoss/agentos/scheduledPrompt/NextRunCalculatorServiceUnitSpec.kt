package io.whozoss.agentos.scheduledPrompt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Unit tests for [NextRunCalculatorService].
 *
 * Each test builds its own [NextRunCalculatorService] instance from a fixed [Clock] so results
 * are deterministic — no Spring context required.
 * "now" is always 2026-01-01 07:00 UTC (a Thursday).
 * Default timeUtc = 08:00 UTC unless stated otherwise.
 */
class NextRunCalculatorServiceUnitSpec : StringSpec({

    // Fixed reference point: 2026-01-01 07:00:00 UTC (Thursday)
    val nowInstant = Instant.parse("2026-01-01T07:00:00Z")
    val nowDate = LocalDate.of(2026, 1, 1) // Thursday
    val calculator = NextRunCalculatorService(clock = Clock.fixed(nowInstant, ZoneOffset.UTC))
    val defaultTime = LocalTime.of(8, 0)
    val agentId = UUID.randomUUID()
    val promptId = UUID.randomUUID()

    fun calculatorAt(instant: Instant) = NextRunCalculatorService(clock = Clock.fixed(instant, ZoneOffset.UTC))

    fun sp(
        startDate: LocalDate = nowDate,
        unit: SchedulerUnit = SchedulerUnit.WEEK,
        days: List<DayOfWeek> = emptyList(),
        timeUtc: LocalTime = defaultTime,
        endType: SchedulerEndType = SchedulerEndType.NEVER,
        endDate: LocalDate? = null,
        maxOccurrenceCount: Int? = null,
    ) = ScheduledPrompt(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        namespaceId = null,
        agentConfigId = agentId,
        promptTemplateId = promptId,
        name = "test",
        recurrence = Recurrence(unit = unit, days = days, timeUtc = timeUtc),
        planning = Planning(
            startDate = startDate,
            endType = endType,
            endDate = endDate,
            maxOccurrenceCount = maxOccurrenceCount,
        ),
        nextRunAt = Instant.EPOCH, // overwritten by calculator
    )

    // =========================================================================
    // WEEK — no day filter (fires on same day-of-week as startDate)
    // =========================================================================

    // nowDate = Thursday 2026-01-01

    "WEEK no filter startDate=Thursday: today still valid" {
        // startDate=Thursday, now=07:00, timeUtc=08:00 → today is valid
        calculator.compute(scheduledPrompt = sp(unit = SchedulerUnit.WEEK)) shouldBe
            Instant.parse("2026-01-01T08:00:00Z")
    }

    "WEEK no filter startDate=Thursday: time passed → next Thursday" {
        val laterCalculator = calculatorAt(Instant.parse("2026-01-01T09:00:00Z"))
        laterCalculator.compute(scheduledPrompt = sp(unit = SchedulerUnit.WEEK)) shouldBe
            Instant.parse("2026-01-08T08:00:00Z")
    }

    "WEEK no filter startDate=Monday: now=Thursday → next Monday = 2026-01-05" {
        val monday = LocalDate.of(2025, 12, 29) // Monday before 'now'
        calculator.compute(scheduledPrompt = sp(startDate = monday, unit = SchedulerUnit.WEEK)) shouldBe
            Instant.parse("2026-01-05T08:00:00Z")
    }

    "WEEK no filter startDate in future: nextRunAt = startDate" {
        val futureMonday = LocalDate.of(2026, 1, 12) // Monday
        calculator.compute(scheduledPrompt = sp(startDate = futureMonday, unit = SchedulerUnit.WEEK)) shouldBe
            Instant.parse("2026-01-12T08:00:00Z")
    }

    // =========================================================================
    // WEEK — with day-of-week filter
    // =========================================================================

    "WEEK days=[THURSDAY]: today is in filter and time not passed → today" {
        calculator.compute(scheduledPrompt = sp(unit = SchedulerUnit.WEEK, days = listOf(DayOfWeek.THURSDAY))) shouldBe
            Instant.parse("2026-01-01T08:00:00Z")
    }

    "WEEK days=[THURSDAY]: today is in filter but time passed → next Thursday" {
        val laterCalculator = calculatorAt(Instant.parse("2026-01-01T09:00:00Z"))
        laterCalculator.compute(scheduledPrompt = sp(unit = SchedulerUnit.WEEK, days = listOf(DayOfWeek.THURSDAY))) shouldBe
            Instant.parse("2026-01-08T08:00:00Z")
    }

    "WEEK days=[TUESDAY, THURSDAY]: now=Thursday 07:00 → today at 08:00" {
        calculator.compute(scheduledPrompt = sp(unit = SchedulerUnit.WEEK, days = listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY))) shouldBe
            Instant.parse("2026-01-01T08:00:00Z")
    }

    "WEEK days=[TUESDAY, THURSDAY]: now=Thursday 09:00 (passed) → next Tuesday 2026-01-06" {
        val laterCalculator = calculatorAt(Instant.parse("2026-01-01T09:00:00Z"))
        laterCalculator.compute(scheduledPrompt = sp(unit = SchedulerUnit.WEEK, days = listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY))) shouldBe
            Instant.parse("2026-01-06T08:00:00Z")
    }

    "WEEK days=[MONDAY]: now=Thursday → next Monday = 2026-01-05" {
        calculator.compute(scheduledPrompt = sp(unit = SchedulerUnit.WEEK, days = listOf(DayOfWeek.MONDAY))) shouldBe
            Instant.parse("2026-01-05T08:00:00Z")
    }

    "WEEK days=[MONDAY, WEDNESDAY, FRIDAY]: now=Thursday → next Friday 2026-01-02" {
        calculator.compute(scheduledPrompt = sp(unit = SchedulerUnit.WEEK, days = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))) shouldBe
            Instant.parse("2026-01-02T08:00:00Z")
    }

    "WEEK days=[MONDAY] startDate in future (2026-01-12 = Monday): nextRunAt = startDate" {
        val futureMonday = LocalDate.of(2026, 1, 12)
        calculator.compute(scheduledPrompt = sp(startDate = futureMonday, unit = SchedulerUnit.WEEK, days = listOf(DayOfWeek.MONDAY))) shouldBe
            Instant.parse("2026-01-12T08:00:00Z")
    }

    // =========================================================================
    // MONTH
    // =========================================================================

    "MONTH startDate=2026-01-01: today still valid" {
        calculator.compute(scheduledPrompt = sp(unit = SchedulerUnit.MONTH)) shouldBe
            Instant.parse("2026-01-01T08:00:00Z")
    }

    "MONTH startDate=2026-01-01: time passed → 2026-02-01" {
        val laterCalculator = calculatorAt(Instant.parse("2026-01-01T09:00:00Z"))
        laterCalculator.compute(scheduledPrompt = sp(unit = SchedulerUnit.MONTH)) shouldBe
            Instant.parse("2026-02-01T08:00:00Z")
    }

    "MONTH startDate=2026-01-31: next month clamps to 2026-02-28" {
        val jan31 = LocalDate.of(2026, 1, 31)
        val laterCalculator = calculatorAt(Instant.parse("2026-01-31T09:00:00Z"))
        laterCalculator.compute(scheduledPrompt = sp(startDate = jan31, unit = SchedulerUnit.MONTH)) shouldBe
            Instant.parse("2026-02-28T08:00:00Z")
    }

    "MONTH startDate=2026-01-31: clamps across multiple months" {
        // 2026-01-31 → 2026-02-28 → 2026-03-31 → 2026-04-30
        val jan31 = LocalDate.of(2026, 1, 31)
        val laterCalculator = calculatorAt(Instant.parse("2026-04-01T09:00:00Z"))
        laterCalculator.compute(scheduledPrompt = sp(startDate = jan31, unit = SchedulerUnit.MONTH)) shouldBe
            Instant.parse("2026-04-30T08:00:00Z")
    }

    "MONTH startDate in future: nextRunAt = startDate" {
        val futureStart = LocalDate.of(2026, 3, 15)
        calculator.compute(scheduledPrompt = sp(startDate = futureStart, unit = SchedulerUnit.MONTH)) shouldBe
            Instant.parse("2026-03-15T08:00:00Z")
    }

    // =========================================================================
    // Planning — startDate boundary
    // =========================================================================

    "startDate in the past: nextRunAt is based on now, not startDate" {
        // startDate=2025-12-01 (Monday), now=2026-01-01 07:00 (Thursday), timeUtc=08:00
        // WEEK no filter → fires every Monday; next Monday after now = 2026-01-05
        val pastStart = LocalDate.of(2025, 12, 1)
        calculator.compute(scheduledPrompt = sp(startDate = pastStart)) shouldBe
            Instant.parse("2026-01-05T08:00:00Z")
    }

    "startDate=today and time not yet passed: nextRunAt = today at timeUtc" {
        calculator.compute(scheduledPrompt = sp(startDate = nowDate, timeUtc = LocalTime.of(8, 0))) shouldBe
            Instant.parse("2026-01-01T08:00:00Z")
    }

    "startDate=today and time already passed: nextRunAt = next Thursday" {
        // startDate=2026-01-01 (Thursday), now=09:00 (time passed), WEEK no filter
        // → fires every Thursday; next Thursday = 2026-01-08
        val laterCalculator = calculatorAt(Instant.parse("2026-01-01T09:00:00Z"))
        laterCalculator.compute(scheduledPrompt = sp(startDate = nowDate, timeUtc = LocalTime.of(8, 0))) shouldBe
            Instant.parse("2026-01-08T08:00:00Z")
    }

    "startDate in future: nextRunAt = startDate at timeUtc regardless of now" {
        val futureStart = LocalDate.of(2026, 6, 15)
        calculator.compute(scheduledPrompt = sp(startDate = futureStart)) shouldBe
            Instant.parse("2026-06-15T08:00:00Z")
    }

    // =========================================================================
    // Different timeUtc values
    // =========================================================================

    "timeUtc=00:00: slot at midnight" {
        // startDate=2026-01-01 (Thursday), now=07:00, slot at 00:00 has passed
        // WEEK no filter → fires every Thursday; next Thursday = 2026-01-08
        calculator.compute(scheduledPrompt = sp(timeUtc = LocalTime.MIDNIGHT)) shouldBe
            Instant.parse("2026-01-08T00:00:00Z")
    }

    "timeUtc=23:59: slot later today" {
        calculator.compute(scheduledPrompt = sp(timeUtc = LocalTime.of(23, 59))) shouldBe
            Instant.parse("2026-01-01T23:59:00Z")
    }

    // =========================================================================
    // Large gaps — regression coverage for the direct ChronoUnit jump
    // (replacing the old one-step-at-a-time while loops)
    // =========================================================================

    "WEEK no filter: startDate 3 years in the past jumps directly to the correct week" {
        // startDate=2023-01-02 (Monday), now=2026-01-01 07:00 (Thursday)
        // Fires every Monday → next Monday on/after now = 2026-01-05
        val threeYearsAgoMonday = LocalDate.of(2023, 1, 2)
        calculator.compute(scheduledPrompt = sp(startDate = threeYearsAgoMonday, unit = SchedulerUnit.WEEK)) shouldBe
            Instant.parse("2026-01-05T08:00:00Z")
    }

    "MONTH: startDate 5 years in the past jumps directly to the correct month" {
        // startDate=2021-01-15, now=2026-01-01 07:00 → fires on the 15th of every month
        // next 15th on/after now = 2026-01-15
        val fiveYearsAgo = LocalDate.of(2021, 1, 15)
        calculator.compute(scheduledPrompt = sp(startDate = fiveYearsAgo, unit = SchedulerUnit.MONTH)) shouldBe
            Instant.parse("2026-01-15T08:00:00Z")
    }

    "MONTH: startDate on the 31st, 2 years in the past, lands correctly with clamping" {
        // startDate=2024-01-31, now=2026-01-01 07:00 → fires on day 31 (clamped) of every month
        // next occurrence on/after now: 2026-01-31
        val twoYearsAgoJan31 = LocalDate.of(2024, 1, 31)
        calculator.compute(scheduledPrompt = sp(startDate = twoYearsAgoJan31, unit = SchedulerUnit.MONTH)) shouldBe
            Instant.parse("2026-01-31T08:00:00Z")
    }

    "MONTH: startDate far in the past, candidate mid-month requires the isBefore correction" {
        // startDate=2020-01-05 (day 5), candidate = 2026-03-20 (mid-month, day 5 already passed in March)
        // → direct jump lands on 2026-03-05 which is before candidate → correction bumps to 2026-04-05
        val laterCalculator = calculatorAt(Instant.parse("2026-03-20T09:00:00Z"))
        val farPast = LocalDate.of(2020, 1, 5)
        laterCalculator.compute(scheduledPrompt = sp(startDate = farPast, unit = SchedulerUnit.MONTH, timeUtc = LocalTime.of(8, 0))) shouldBe
            Instant.parse("2026-04-05T08:00:00Z")
    }

    "WEEK no filter: candidate mid-week requires the isBefore correction after a large jump" {
        // startDate=2020-01-06 (Monday), candidate day = Thursday 2026-01-01 09:00 (after 08:00 slot)
        // → direct jump lands on the Monday of that week, which is before candidate → next Monday
        val laterCalculator = calculatorAt(Instant.parse("2026-01-01T09:00:00Z"))
        val farPastMonday = LocalDate.of(2020, 1, 6)
        laterCalculator.compute(scheduledPrompt = sp(startDate = farPastMonday, unit = SchedulerUnit.WEEK, timeUtc = LocalTime.of(8, 0))) shouldBe
            Instant.parse("2026-01-05T08:00:00Z")
    }
})
