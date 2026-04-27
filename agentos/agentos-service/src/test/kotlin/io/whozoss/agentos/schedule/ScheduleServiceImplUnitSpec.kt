package io.whozoss.agentos.schedule

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.schedule.IntervalSchedule
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [ScheduleServiceImpl].
 *
 * Focuses on the [create] behaviour: [nextTriggerAt] must be computed from the
 * chosen trigger strategy when not already provided by the caller.
 */
class ScheduleServiceImplUnitSpec : StringSpec({
    timeout = 5000

    val repo = mockk<ScheduleRepository>()
    val service = ScheduleServiceImpl(repo)

    val namespaceId: UUID = UUID.randomUUID()

    // Capture the Schedule passed to repo.save so we can assert on it.
    val savedSlot = slot<Schedule>()
    every { repo.save(capture(savedSlot)) } answers { savedSlot.captured }

    // -------------------------------------------------------------------------
    // create — nextTriggerAt initialisation
    // -------------------------------------------------------------------------

    "create computes nextTriggerAt from triggerAt when not provided" {
        val triggerAt = Instant.now().plusSeconds(3600)
        val schedule = Schedule(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            message = "one-shot",
            triggerAt = triggerAt,
        )

        service.create(schedule)

        savedSlot.captured.nextTriggerAt shouldBe triggerAt
    }

    "create computes nextTriggerAt from intervalSchedule when not provided" {
        val start = Instant.now().plusSeconds(60)
        val schedule = Schedule(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            message = "recurring",
            intervalSchedule = IntervalSchedule(
                startTimestamp = start,
                interval = "1h",
            ),
        )

        service.create(schedule)

        // nextTriggerAt must be at or after `start` (first occurrence >= now)
        savedSlot.captured.nextTriggerAt.shouldNotBeNull() shouldBe start
    }

    "create leaves nextTriggerAt null when no trigger strategy is set" {
        val schedule = Schedule(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            message = "no strategy",
        )

        service.create(schedule)

        savedSlot.captured.nextTriggerAt.shouldBeNull()
    }

    "create preserves nextTriggerAt when caller already provides it" {
        val explicit = Instant.parse("2025-12-31T23:59:59Z")
        val schedule = Schedule(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            message = "pre-computed",
            triggerAt = Instant.now().plusSeconds(3600),
            nextTriggerAt = explicit,
        )

        service.create(schedule)

        savedSlot.captured.nextTriggerAt shouldBe explicit
    }

    // -------------------------------------------------------------------------
    // update — strategy unchanged: preserve existing nextTriggerAt
    // -------------------------------------------------------------------------

    "update preserves nextTriggerAt when trigger strategy is unchanged" {
        val existingNextTrigger = Instant.parse("2025-12-01T10:00:00Z")
        val triggerAt = Instant.now().plusSeconds(3600)
        val existing = Schedule(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            message = "original",
            triggerAt = triggerAt,
            nextTriggerAt = existingNextTrigger,
        )
        every { repo.findByIds(listOf(existing.id)) } returns listOf(existing)

        val updated = existing.copy(message = "updated message")
        service.update(updated)

        savedSlot.captured.nextTriggerAt shouldBe existingNextTrigger
    }

    // -------------------------------------------------------------------------
    // update — triggerAt changed: recompute nextTriggerAt
    // -------------------------------------------------------------------------

    "update recomputes nextTriggerAt when triggerAt changes" {
        val oldTrigger = Instant.parse("2025-06-01T10:00:00Z")
        val newTrigger = Instant.parse("2025-12-01T10:00:00Z")
        val existing = Schedule(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            message = "original",
            triggerAt = oldTrigger,
            nextTriggerAt = oldTrigger,
        )
        every { repo.findByIds(listOf(existing.id)) } returns listOf(existing)

        val updated = existing.copy(triggerAt = newTrigger, nextTriggerAt = null)
        service.update(updated)

        savedSlot.captured.nextTriggerAt shouldBe newTrigger
    }

    // -------------------------------------------------------------------------
    // update — intervalSchedule changed: recompute nextTriggerAt
    // -------------------------------------------------------------------------

    "update recomputes nextTriggerAt when intervalSchedule changes" {
        val oldInterval = IntervalSchedule(
            startTimestamp = Instant.parse("2025-01-01T00:00:00Z"),
            interval = "1d",
        )
        val newInterval = IntervalSchedule(
            startTimestamp = Instant.now().plusSeconds(60),
            interval = "1h",
        )
        val existing = Schedule(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            message = "original",
            intervalSchedule = oldInterval,
            nextTriggerAt = Instant.parse("2025-01-02T00:00:00Z"),
        )
        every { repo.findByIds(listOf(existing.id)) } returns listOf(existing)

        val updated = existing.copy(intervalSchedule = newInterval, nextTriggerAt = null)
        service.update(updated)

        savedSlot.captured.nextTriggerAt.shouldNotBeNull()
    }

    // -------------------------------------------------------------------------
    // update — strategy cleared: nextTriggerAt becomes null
    // -------------------------------------------------------------------------

    "update sets nextTriggerAt to null when trigger strategy is cleared" {
        val existing = Schedule(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            message = "original",
            triggerAt = Instant.parse("2025-06-01T10:00:00Z"),
            nextTriggerAt = Instant.parse("2025-06-01T10:00:00Z"),
        )
        every { repo.findByIds(listOf(existing.id)) } returns listOf(existing)

        // User clears triggerAt without setting intervalSchedule
        val updated = existing.copy(triggerAt = null, nextTriggerAt = null)
        service.update(updated)

        savedSlot.captured.nextTriggerAt shouldBe null
    }
})
