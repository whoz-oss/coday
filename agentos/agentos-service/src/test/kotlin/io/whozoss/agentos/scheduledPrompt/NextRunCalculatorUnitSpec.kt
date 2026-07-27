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
        every: Int = 1,
        unit: SchedulerUnit = SchedulerUnit.DAY,
        days: List<DayOfWeek> = emptyList(),
        timeUtc: LocalTime = defaultTime,
        endType: SchedulerEndType = SchedulerEndType.NEVER,
        endDate: LocalDate? = null,
        occurrenceCount: Int? = null,
    ) = ScheduledPrompt(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        namespaceId = null,
        agentConfigId = agentId,
        promptTemplateId = promptId,
        name = "test",
        recurrence = Recurrence(every = every, unit = unit, days = days, timeUtc = timeUtc),
        planning = Planning(
            startDate = startDate,
            endType = endType,
            endDate = endDate,
            occurrenceCount = occurrenceCount,
        ),
        nextRunAt = Instant.EPOCH, // overwritten by calculator
    )

    // =========================================================================
    // DAY — no day filter
    // =========================================================================

    "DAY every=1 no filter: time not yet passed today → today" {
        // now=07:00, timeUtc=08:00 → slot is 08:00 today, still in the future
        NextRunCalculator.compute(sp(), clock) shouldBe Instant.parse("2026-01-01T08:00:00Z")
    }

    "DAY every=1 no filter: time already passed today → tomorrow" {
        // now=09:00, timeUtc=08:00 → slot has passed, next is tomorrow
        val laterClock = Clock.fixed(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC)
        NextRunCalculator.compute(sp(), laterClock) shouldBe Instant.parse("2026-01-02T08:00:00Z")
    }

    "DAY every=2 no filter: next slot in 2-day stride" {
        // startDate=2026-01-01, now=07:00, every=2 → slot on 2026-01-01 at 08:00 is still valid
        NextRunCalculator.compute(sp(every = 2), clock) shouldBe Instant.parse("2026-01-01T08:00:00Z")
    }

    "DAY every=2 no filter: time passed, next stride" {
        val laterClock = Clock.fixed(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC)
        // startDate=2026-01-01, every=2 → next valid date = 2026-01-03
        NextRunCalculator.compute(sp(every = 2), laterClock) shouldBe Instant.parse("2026-01-03T08:00:00Z")
    }

    "DAY every=1 startDate in the future: nextRunAt not before startDate" {
        val futureStart = nowDate.plusDays(5) // 2026-01-06
        NextRunCalculator.compute(sp(startDate = futureStart), clock) shouldBe
            Instant.parse("2026-01-06T08:00:00Z")
    }

    // =========================================================================
    // DAY — with day-of-week filter
    // =========================================================================

    // nowDate = 2026-01-01 = Thursday

    "DAY days=[THURSDAY]: today is in filter and time not passed → today" {
        NextRunCalculator.compute(sp(days = listOf(DayOfWeek.THURSDAY)), clock) shouldBe
            Instant.parse("2026-01-01T08:00:00Z")
    }

    "DAY days=[THURSDAY]: today is in filter but time passed → next Thursday" {
        val laterClock = Clock.fixed(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC)
        NextRunCalculator.compute(sp(days = listOf(DayOfWeek.THURSDAY)), laterClock) shouldBe
            Instant.parse("2026-01-08T08:00:00Z")
    }

    "DAY days=[MONDAY, WEDNESDAY, FRIDAY]: next is Friday 2026-01-02" {
        // Thursday 07:00 → next valid day in [MON, WED, FRI] is Friday 2026-01-02
        NextRunCalculator.compute(sp(days = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)), clock) shouldBe
            Instant.parse("2026-01-02T08:00:00Z")
    }

    "DAY days=[MONDAY]: next Monday from Thursday" {
        // Thursday → next Monday = 2026-01-05
        NextRunCalculator.compute(sp(days = listOf(DayOfWeek.MONDAY)), clock) shouldBe
            Instant.parse("2026-01-05T08:00:00Z")
    }

    "DAY days=[TUESDAY] every=2: only TUESDAY matters (every ignored), next Tuesday = 2026-01-06" {
        // Thursday 2026-01-01 → next Tuesday = 2026-01-06
        NextRunCalculator.compute(sp(every = 2, days = listOf(DayOfWeek.TUESDAY)), clock) shouldBe
            Instant.parse("2026-01-06T08:00:00Z")
    }

    "DAY days=[MONDAY] startDate in future (2026-01-12 = Monday): nextRunAt = startDate" {
        // startDate=2026-01-12 (Monday), now=2026-01-01 → candidate = startDate at 08:00
        val futureMonday = LocalDate.of(2026, 1, 12)
        NextRunCalculator.compute(sp(startDate = futureMonday, days = listOf(DayOfWeek.MONDAY)), clock) shouldBe
            Instant.parse("2026-01-12T08:00:00Z")
    }

    // =========================================================================
    // WEEK
    // =========================================================================

    // nowDate = Thursday 2026-01-01

    "WEEK every=1 startDate=Thursday: same day each week, today still valid" {
        // startDate=Thursday, now=07:00, timeUtc=08:00 → today is valid
        NextRunCalculator.compute(sp(unit = SchedulerUnit.WEEK), clock) shouldBe
            Instant.parse("2026-01-01T08:00:00Z")
    }

    "WEEK every=1 startDate=Thursday: time passed → next Thursday" {
        val laterClock = Clock.fixed(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC)
        NextRunCalculator.compute(sp(unit = SchedulerUnit.WEEK), laterClock) shouldBe
            Instant.parse("2026-01-08T08:00:00Z")
    }

    "WEEK every=2 startDate=Thursday: next slot = 2026-01-15 (2 weeks out) when today passed" {
        val laterClock = Clock.fixed(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC)
        NextRunCalculator.compute(sp(unit = SchedulerUnit.WEEK, every = 2), laterClock) shouldBe
            Instant.parse("2026-01-15T08:00:00Z")
    }

    "WEEK every=1 startDate=Monday: now=Thursday → next Monday = 2026-01-05" {
        val monday = LocalDate.of(2025, 12, 29) // Monday before our 'now'
        NextRunCalculator.compute(sp(startDate = monday, unit = SchedulerUnit.WEEK), clock) shouldBe
            Instant.parse("2026-01-05T08:00:00Z")
    }

    "WEEK every=1 startDate in future: nextRunAt = startDate" {
        val futureMonday = LocalDate.of(2026, 1, 12) // Monday
        NextRunCalculator.compute(sp(startDate = futureMonday, unit = SchedulerUnit.WEEK), clock) shouldBe
            Instant.parse("2026-01-12T08:00:00Z")
    }

    // =========================================================================
    // MONTH
    // =========================================================================

    "MONTH every=1 startDate=2026-01-01: today still valid" {
        NextRunCalculator.compute(sp(unit = SchedulerUnit.MONTH), clock) shouldBe
            Instant.parse("2026-01-01T08:00:00Z")
    }

    "MONTH every=1 startDate=2026-01-01: time passed → 2026-02-01" {
        val laterClock = Clock.fixed(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC)
        NextRunCalculator.compute(sp(unit = SchedulerUnit.MONTH), laterClock) shouldBe
            Instant.parse("2026-02-01T08:00:00Z")
    }

    "MONTH every=3 startDate=2026-01-01: time passed → 2026-04-01" {
        val laterClock = Clock.fixed(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC)
        NextRunCalculator.compute(sp(unit = SchedulerUnit.MONTH, every = 3), laterClock) shouldBe
            Instant.parse("2026-04-01T08:00:00Z")
    }

    "MONTH startDate=2026-01-31: next month clamps to 2026-02-28" {
        val jan31 = LocalDate.of(2026, 1, 31)
        val laterClock = Clock.fixed(Instant.parse("2026-01-31T09:00:00Z"), ZoneOffset.UTC)
        NextRunCalculator.compute(sp(startDate = jan31, unit = SchedulerUnit.MONTH), laterClock) shouldBe
            Instant.parse("2026-02-28T08:00:00Z")
    }

    "MONTH startDate=2026-01-31 every=3: clamps in short months" {
        // 2026-01-31 + 3 months = 2026-04-30 (April has 30 days, no clamp needed)
        val jan31 = LocalDate.of(2026, 1, 31)
        val laterClock = Clock.fixed(Instant.parse("2026-01-31T09:00:00Z"), ZoneOffset.UTC)
        NextRunCalculator.compute(sp(startDate = jan31, unit = SchedulerUnit.MONTH, every = 3), laterClock) shouldBe
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
        // startDate=2025-12-01 (past), now=2026-01-01 07:00, timeUtc=08:00 → today is valid
        val pastStart = LocalDate.of(2025, 12, 1)
        NextRunCalculator.compute(sp(startDate = pastStart), clock) shouldBe
            Instant.parse("2026-01-01T08:00:00Z")
    }

    "startDate=today and time not yet passed: nextRunAt = today at timeUtc" {
        NextRunCalculator.compute(sp(startDate = nowDate, timeUtc = LocalTime.of(8, 0)), clock) shouldBe
            Instant.parse("2026-01-01T08:00:00Z")
    }

    "startDate=today and time already passed: nextRunAt = tomorrow" {
        val laterClock = Clock.fixed(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC)
        NextRunCalculator.compute(sp(startDate = nowDate, timeUtc = LocalTime.of(8, 0)), laterClock) shouldBe
            Instant.parse("2026-01-02T08:00:00Z")
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
        // now=07:00, slot at 00:00 has passed → next day
        NextRunCalculator.compute(sp(timeUtc = LocalTime.MIDNIGHT), clock) shouldBe
            Instant.parse("2026-01-02T00:00:00Z")
    }

    "timeUtc=23:59: slot later today" {
        NextRunCalculator.compute(sp(timeUtc = LocalTime.of(23, 59)), clock) shouldBe
            Instant.parse("2026-01-01T23:59:00Z")
    }
})
