package io.whozoss.agentos.schedule

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.schedule.EndCondition
import io.whozoss.agentos.sdk.schedule.IntervalSchedule
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class ScheduleTriggerCalculatorSpec : StringSpec({

    val namespaceId: UUID = UUID.randomUUID()
    val t0: Instant = Instant.parse("2025-01-01T08:00:00Z")

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    fun oneShot(triggerAt: Instant) = Schedule(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        message = "ping",
        triggerAt = triggerAt,
        oneShot = true,
    )

    fun recurring(
        start: Instant = t0,
        interval: String,
        daysOfWeek: List<Int>? = null,
        endCondition: EndCondition? = null,
        oneShot: Boolean = false,
    ) = Schedule(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        message = "ping",
        intervalSchedule = IntervalSchedule(
            startTimestamp = start,
            interval = interval,
            daysOfWeek = daysOfWeek,
            endCondition = endCondition,
        ),
        oneShot = oneShot,
    )

    // ------------------------------------------------------------------
    // computeInitialTriggerAt
    // ------------------------------------------------------------------

    "computeInitialTriggerAt returns triggerAt for a one-shot schedule" {
        val schedule = oneShot(t0.plus(1, ChronoUnit.HOURS))
        val result = ScheduleTriggerCalculator.computeInitialTriggerAt(schedule, t0)
        result shouldBe t0.plus(1, ChronoUnit.HOURS)
    }

    "computeInitialTriggerAt returns startTimestamp itself when it is in the future" {
        val start = t0.plus(30, ChronoUnit.MINUTES)
        val schedule = recurring(start = start, interval = "1h")
        val result = ScheduleTriggerCalculator.computeInitialTriggerAt(schedule, t0)
        result shouldBe start
    }

    "computeInitialTriggerAt advances past startTimestamp when it is in the past" {
        // start is 2 h before now; interval is 1h → first occurrence at or after now is start + 2h
        val start = t0.minus(2, ChronoUnit.HOURS)
        val schedule = recurring(start = start, interval = "1h")
        val result = ScheduleTriggerCalculator.computeInitialTriggerAt(schedule, t0)
        result shouldBe t0 // start + 2h == t0
    }

    "computeInitialTriggerAt returns null when neither strategy is set" {
        val empty = Schedule(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            message = "ping",
        )
        ScheduleTriggerCalculator.computeInitialTriggerAt(empty, t0).shouldBeNull()
    }

    // ------------------------------------------------------------------
    // computeNextTriggerAt — basic intervals
    // ------------------------------------------------------------------

    "computeNextTriggerAt advances by minutes" {
        val schedule = recurring(interval = "30min")
        val next = ScheduleTriggerCalculator.computeNextTriggerAt(schedule, t0, occurrenceCount = 1)
        next shouldBe t0.plus(30, ChronoUnit.MINUTES)
    }

    "computeNextTriggerAt advances by hours" {
        val schedule = recurring(interval = "6h")
        val next = ScheduleTriggerCalculator.computeNextTriggerAt(schedule, t0, occurrenceCount = 1)
        next shouldBe t0.plus(6, ChronoUnit.HOURS)
    }

    "computeNextTriggerAt advances by days" {
        val schedule = recurring(interval = "1d")
        val next = ScheduleTriggerCalculator.computeNextTriggerAt(schedule, t0, occurrenceCount = 1)
        next shouldBe t0.plus(1, ChronoUnit.DAYS)
    }

    "computeNextTriggerAt advances by months" {
        // 2025-01-01 + 1M = 2025-02-01
        val schedule = recurring(interval = "1M")
        val next = ScheduleTriggerCalculator.computeNextTriggerAt(schedule, t0, occurrenceCount = 1)
        next shouldBe Instant.parse("2025-02-01T08:00:00Z")
    }

    "computeNextTriggerAt returns null for a one-shot triggerAt schedule" {
        val schedule = oneShot(t0)
        val next = ScheduleTriggerCalculator.computeNextTriggerAt(schedule, t0, occurrenceCount = 1)
        next.shouldBeNull()
    }

    // ------------------------------------------------------------------
    // computeNextTriggerAt — EndCondition
    // ------------------------------------------------------------------

    "computeNextTriggerAt returns null when Occurrences end condition is reached" {
        val schedule = recurring(interval = "1h", endCondition = EndCondition.Occurrences(count = 3))
        // occurrenceCount = 3 means we have already fired 3 times
        val next = ScheduleTriggerCalculator.computeNextTriggerAt(schedule, t0, occurrenceCount = 3)
        next.shouldBeNull()
    }

    "computeNextTriggerAt returns next when Occurrences limit not yet reached" {
        val schedule = recurring(interval = "1h", endCondition = EndCondition.Occurrences(count = 3))
        val next = ScheduleTriggerCalculator.computeNextTriggerAt(schedule, t0, occurrenceCount = 2)
        next shouldBe t0.plus(1, ChronoUnit.HOURS)
    }

    "computeNextTriggerAt returns null when EndTimestamp is in the past" {
        val deadline = t0.plus(30, ChronoUnit.MINUTES)
        val schedule = recurring(interval = "1h", endCondition = EndCondition.EndTimestamp(deadline))
        // next would be t0 + 1h, which is after deadline
        val next = ScheduleTriggerCalculator.computeNextTriggerAt(schedule, t0, occurrenceCount = 1)
        next.shouldBeNull()
    }

    "computeNextTriggerAt returns next when EndTimestamp is in the future" {
        val deadline = t0.plus(2, ChronoUnit.HOURS)
        val schedule = recurring(interval = "1h", endCondition = EndCondition.EndTimestamp(deadline))
        val next = ScheduleTriggerCalculator.computeNextTriggerAt(schedule, t0, occurrenceCount = 1)
        next shouldBe t0.plus(1, ChronoUnit.HOURS)
    }

    // ------------------------------------------------------------------
    // computeNextTriggerAt — daysOfWeek filter
    // ------------------------------------------------------------------

    "computeNextTriggerAt skips days not in daysOfWeek filter" {
        // t0 = 2025-01-01 (Wednesday, ISO 3, mapped to 3 in 0=Sun..6=Sat)
        // Only allow Saturday (6) and Sunday (0)
        val schedule = recurring(interval = "1d", daysOfWeek = listOf(0, 6))
        // From t0 (Wed) + 1d = Thu, not allowed
        // + 2d = Fri, not allowed
        // + 3d = Sat (6), allowed → 2025-01-04
        val next = ScheduleTriggerCalculator.computeNextTriggerAt(schedule, t0, occurrenceCount = 1)
        next shouldBe Instant.parse("2025-01-04T08:00:00Z")
    }

    // ------------------------------------------------------------------
    // isExpiredAfterTrigger
    // ------------------------------------------------------------------

    "isExpiredAfterTrigger returns true for oneShot=true" {
        val schedule = recurring(interval = "1h", oneShot = true)
        ScheduleTriggerCalculator.isExpiredAfterTrigger(schedule, occurrenceCount = 1) shouldBe true
    }

    "isExpiredAfterTrigger returns true for triggerAt schedule" {
        val schedule = oneShot(t0)
        ScheduleTriggerCalculator.isExpiredAfterTrigger(schedule, occurrenceCount = 1) shouldBe true
    }

    "isExpiredAfterTrigger returns false for open-ended recurring schedule" {
        val schedule = recurring(interval = "1h")
        ScheduleTriggerCalculator.isExpiredAfterTrigger(schedule, occurrenceCount = 99) shouldBe false
    }

    "isExpiredAfterTrigger returns true when Occurrences limit reached" {
        val schedule = recurring(interval = "1h", endCondition = EndCondition.Occurrences(count = 5))
        ScheduleTriggerCalculator.isExpiredAfterTrigger(schedule, occurrenceCount = 5) shouldBe true
    }

    "isExpiredAfterTrigger returns false when Occurrences limit not yet reached" {
        val schedule = recurring(interval = "1h", endCondition = EndCondition.Occurrences(count = 5))
        ScheduleTriggerCalculator.isExpiredAfterTrigger(schedule, occurrenceCount = 4) shouldBe false
    }

    // ------------------------------------------------------------------
    // applyCatchUpPolicy
    // ------------------------------------------------------------------

    "applyCatchUpPolicy ALL returns all schedules" {
        val s1 = recurring(interval = "1h").copy(nextTriggerAt = t0.minus(2, ChronoUnit.HOURS))
        val s2 = recurring(interval = "1h").copy(nextTriggerAt = t0.minus(1, ChronoUnit.HOURS))
        ScheduleTriggerCalculator.applyCatchUpPolicy(listOf(s1, s2), CatchUpPolicy.ALL) shouldBe listOf(s1, s2)
    }

    "applyCatchUpPolicy NONE returns empty list" {
        val s1 = recurring(interval = "1h").copy(nextTriggerAt = t0.minus(1, ChronoUnit.HOURS))
        ScheduleTriggerCalculator.applyCatchUpPolicy(listOf(s1), CatchUpPolicy.NONE) shouldBe emptyList()
    }

    "applyCatchUpPolicy FIRST returns oldest schedule" {
        val older = recurring(interval = "1h").copy(nextTriggerAt = t0.minus(2, ChronoUnit.HOURS))
        val newer = recurring(interval = "1h").copy(nextTriggerAt = t0.minus(1, ChronoUnit.HOURS))
        ScheduleTriggerCalculator.applyCatchUpPolicy(listOf(newer, older), CatchUpPolicy.FIRST) shouldBe listOf(older)
    }

    "applyCatchUpPolicy LAST returns most recent schedule" {
        val older = recurring(interval = "1h").copy(nextTriggerAt = t0.minus(2, ChronoUnit.HOURS))
        val newer = recurring(interval = "1h").copy(nextTriggerAt = t0.minus(1, ChronoUnit.HOURS))
        ScheduleTriggerCalculator.applyCatchUpPolicy(listOf(older, newer), CatchUpPolicy.LAST) shouldBe listOf(newer)
    }
})
