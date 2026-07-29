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
 * Unit tests for [NextRunCalculator].
 *
 * All tests inject a fixed [Clock] so results are deterministic.
 * "now" is always 2026-01-01 07:00 UTC (a Thursday).
 * Default timeUtc = 08:00 UTC unless stated otherwise.
 */
class NextRunCalculatorUnitSpec : StringSpec({

    // Fixed reference point: 2026-01-01 07:00:00 UTC (Thursday)
    val nowInstant = Instant.parse("2026-01-01T07:00:00Z")
    val nowDate = LocalDate.of(2026, 1, 1) // Thursday
    val clock = Clock.fixed(nowInstant, ZoneOffset.UTC)
    val defaultTime = LocalTime.of(8, 0)
    val agentId = UUID.randomUUID()
    val promptId = UUID.randomUUID()

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
        NextRunCalculator.compute(sp(unit = SchedulerUnit.WEEK), clock) shouldBe
            Instant.parse("2026-01-01T08:00:00Z")
    }

    "WEEK no filter startDate=Thursday: time passed → next Thursday" {
        val laterClock = Clock.fixed(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC)
        NextRunCalculator.compute(sp(unit = SchedulerUnit.WEEK), laterClock) shouldBe
            Instant.parse("2026-01-08T08:00:00Z")
    }

    "WEEK no filter startDate=Monday: now=Thursday → next Monday = 2026-01-05" {
        val monday = LocalDate.of(2025, 12, 29) // Monday before 'now'
        NextRunCalculator.compute(sp(startDate = monday, unit = SchedulerUnit.WEEK), clock) shouldBe
            Instant.parse("2026-01-05T08:00:00Z")
    }

    "WEEK no filter startDate in future: nextRunAt = startDate" {
        val futureMonday = LocalDate.of(2026, 1, 12) // Monday
        NextRunCalculator.compute(sp(startDate = futureMonday, unit = SchedulerUnit.WEEK), clock) shouldBe
            Instant.parse("2026-01-12T08:00:00Z")
    }

    // =========================================================================
    // WEEK — with day-of-week filter
    // =========================================================================

    "WEEK days=[THURSDAY]: today is in filter and time not passed → today" {
        NextRunCalculator.compute(sp(unit = SchedulerUnit.WEEK, days = listOf(DayOfWeek.THURSDAY)), clock) shouldBe
            Instant.parse("2026-01-01T08:00:00Z")
    }

    "WEEK days=[THURSDAY]: today is in filter but time passed → next Thursday" {
        val laterClock = Clock.fixed(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC)
        NextRunCalculator.compute(sp(unit = SchedulerUnit.WEEK, days = listOf(DayOfWeek.THURSDAY)), laterClock) shouldBe
            Instant.parse("2026-01-08T08:00:00Z")
    }

    "WEEK days=[TUESDAY, THURSDAY]: now=Thursday 07:00 → today at 08:00" {
        NextRunCalculator.compute(sp(unit = SchedulerUnit.WEEK, days = listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)), clock) shouldBe
            Instant.parse("2026-01-01T08:00:00Z")
    }

    "WEEK days=[TUESDAY, THURSDAY]: now=Thursday 09:00 (passed) → next Tuesday 2026-01-06" {
        val laterClock = Clock.fixed(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC)
        NextRunCalculator.compute(sp(unit = SchedulerUnit.WEEK, days = listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)), laterClock) shouldBe
            Instant.parse("2026-01-06T08:00:00Z")
    }

    "WEEK days=[MONDAY]: now=Thursday → next Monday = 2026-01-05" {
        NextRunCalculator.compute(sp(unit = SchedulerUnit.WEEK, days = listOf(DayOfWeek.MONDAY)), clock) shouldBe
            Instant.parse("2026-01-05T08:00:00Z")
    }

    "WEEK days=[MONDAY, WEDNESDAY, FRIDAY]: now=Thursday → next Friday 2026-01-02" {
        NextRunCalculator.compute(sp(unit = SchedulerUnit.WEEK, days = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)), clock) shouldBe
            Instant.parse("2026-01-02T08:00:00Z")
    }

    "WEEK days=[MONDAY] startDate in future (2026-01-12 = Monday): nextRunAt = startDate" {
        val futureMonday = LocalDate.of(2026, 1, 12)
        NextRunCalculator.compute(sp(startDate = futureMonday, unit = SchedulerUnit.WEEK, days = listOf(DayOfWeek.MONDAY)), clock) shouldBe
            Instant.parse("2026-01-12T08:00:00Z")
    }

    // =========================================================================
    // MONTH
    // =========================================================================

    "MONTH startDate=2026-01-01: today still valid" {
        NextRunCalculator.compute(sp(unit = SchedulerUnit.MONTH), clock) shouldBe
            Instant.parse("2026-01-01T08:00:00Z")
    }

    "MONTH startDate=2026-01-01: time passed → 2026-02-01" {
        val laterClock = Clock.fixed(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC)
        NextRunCalculator.compute(sp(unit = SchedulerUnit.MONTH), laterClock) shouldBe
            Instant.parse("2026-02-01T08:00:00Z")
    }

    "MONTH startDate=2026-01-31: next month clamps to 2026-02-28" {
        val jan31 = LocalDate.of(2026, 1, 31)
        val laterClock = Clock.fixed(Instant.parse("2026-01-31T09:00:00Z"), ZoneOffset.UTC)
        NextRunCalculator.compute(sp(startDate = jan31, unit = SchedulerUnit.MONTH), laterClock) shouldBe
            Instant.parse("2026-02-28T08:00:00Z")
    }

    "MONTH startDate=2026-01-31: clamps across multiple months" {
        // 2026-01-31 → 2026-02-28 → 2026-03-31 → 2026-04-30
        val jan31 = LocalDate.of(2026, 1, 31)
        val laterClock = Clock.fixed(Instant.parse("2026-04-01T09:00:00Z"), ZoneOffset.UTC)
        NextRunCalculator.compute(sp(startDate = jan31, unit = SchedulerUnit.MONTH), laterClock) shouldBe
            Instant.parse("2026-04-30T08:00:00Z")
    }

    "MONTH startDate in future: nextRunAt = startDate" {
        val futureStart = LocalDate.of(2026, 3, 15)
        NextRunCalculator.compute(sp(startDate = futureStart, unit = SchedulerUnit.MONTH), clock) shouldBe
            Instant.parse("2026-03-15T08:00:00Z")
    }

    // =========================================================================
    // Planning — startDate boundary
    // =========================================================================

    "startDate in the past: nextRunAt is based on now, not startDate" {
        // startDate=2025-12-01 (Monday), now=2026-01-01 07:00 (Thursday), timeUtc=08:00
        // WEEK no filter → fires every Monday; next Monday after now = 2026-01-05
        val pastStart = LocalDate.of(2025, 12, 1)
        NextRunCalculator.compute(sp(startDate = pastStart), clock) shouldBe
            Instant.parse("2026-01-05T08:00:00Z")
    }

    "startDate=today and time not yet passed: nextRunAt = today at timeUtc" {
        NextRunCalculator.compute(sp(startDate = nowDate, timeUtc = LocalTime.of(8, 0)), clock) shouldBe
            Instant.parse("2026-01-01T08:00:00Z")
    }

    "startDate=today and time already passed: nextRunAt = next Thursday" {
        // startDate=2026-01-01 (Thursday), now=09:00 (time passed), WEEK no filter
        // → fires every Thursday; next Thursday = 2026-01-08
        val laterClock = Clock.fixed(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC)
        NextRunCalculator.compute(sp(startDate = nowDate, timeUtc = LocalTime.of(8, 0)), laterClock) shouldBe
            Instant.parse("2026-01-08T08:00:00Z")
    }

    "startDate in future: nextRunAt = startDate at timeUtc regardless of now" {
        val futureStart = LocalDate.of(2026, 6, 15)
        NextRunCalculator.compute(sp(startDate = futureStart), clock) shouldBe
            Instant.parse("2026-06-15T08:00:00Z")
    }

    // =========================================================================
    // Different timeUtc values
    // =========================================================================

    "timeUtc=00:00: slot at midnight" {
        // startDate=2026-01-01 (Thursday), now=07:00, slot at 00:00 has passed
        // WEEK no filter → fires every Thursday; next Thursday = 2026-01-08
        NextRunCalculator.compute(sp(timeUtc = LocalTime.MIDNIGHT), clock) shouldBe
            Instant.parse("2026-01-08T00:00:00Z")
    }

    "timeUtc=23:59: slot later today" {
        NextRunCalculator.compute(sp(timeUtc = LocalTime.of(23, 59)), clock) shouldBe
            Instant.parse("2026-01-01T23:59:00Z")
    }
})
