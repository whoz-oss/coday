package io.whozoss.agentos.schedule

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.schedule.EndCondition
import io.whozoss.agentos.sdk.schedule.IntervalSchedule
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Unit tests for [ScheduleExecutorService].
 *
 * All dependencies are mocked. Tests call [ScheduleExecutorService.poll] directly
 * (no Spring scheduling involved) so the timing loop is bypassed.
 *
 * Note on [ScheduleRepository.save] stubbing: MockK's relaxed mode returns a generic
 * [io.whozoss.agentos.sdk.entity.Entity] subclass for the covariant return type, which
 * causes a [ClassCastException] when the service casts it back to [Schedule]. Each test
 * that exercises [fire] (and therefore calls [save]) must stub save explicitly via
 * `every { scheduleRepository.save(any()) } answers { firstArg() }`.
 */
class ScheduleExecutorServiceUnitSpec : StringSpec({
    timeout = 5000

    val scheduleRepository = mockk<ScheduleRepository>(relaxed = true)
    val caseService = mockk<CaseService>(relaxed = true)
    val userService = mockk<UserService>(relaxed = true)
    val config = SchedulerConfig(catchUpPolicy = CatchUpPolicy.LAST)

    val executor = ScheduleExecutorService(scheduleRepository, caseService, userService, config)

    val namespaceId: UUID = UUID.randomUUID()
    val caseId: UUID = UUID.randomUUID()

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    fun dueSchedule(
        id: UUID = UUID.randomUUID(),
        caseIdOverride: UUID? = caseId,
        userId: UUID? = null,
        oneShot: Boolean = false,
        triggerAt: Instant? = null,
        intervalSchedule: IntervalSchedule? = null,
        nextTriggerAt: Instant = Instant.now().minusSeconds(10),
        occurrenceCount: Int = 0,
    ) = Schedule(
        metadata = EntityMetadata(id = id),
        namespaceId = namespaceId,
        caseId = caseIdOverride,
        userId = userId,
        message = "hello",
        enabled = true,
        oneShot = oneShot,
        triggerAt = triggerAt,
        intervalSchedule = intervalSchedule,
        nextTriggerAt = nextTriggerAt,
        occurrenceCount = occurrenceCount,
    )

    // ------------------------------------------------------------------
    // poll — no-op when no due schedules
    // ------------------------------------------------------------------

    "poll does nothing when no schedules are due" {
        every { scheduleRepository.findDueSchedules(any()) } returns emptyList()

        executor.poll()

        verify(exactly = 0) { caseService.addMessage(any(), any(), any(), any()) }
        verify(exactly = 0) { scheduleRepository.save(any()) }
    }

    // ------------------------------------------------------------------
    // poll — fires a due schedule and adds the message
    // ------------------------------------------------------------------

    "poll fires a due schedule and calls addMessage" {
        val schedule = dueSchedule(intervalSchedule = IntervalSchedule(
            startTimestamp = Instant.now().minus(1, ChronoUnit.HOURS),
            interval = "1h",
        ))
        every { scheduleRepository.findDueSchedules(any()) } returns listOf(schedule)
        every { scheduleRepository.save(any()) } answers { firstArg() }

        executor.poll()

        verify(exactly = 1) { caseService.addMessage(eq(caseId), any(), any(), any()) }
    }

    // ------------------------------------------------------------------
    // fire — oneShot schedule is disabled after trigger
    // ------------------------------------------------------------------

    "fire disables a oneShot schedule after it fires" {
        val schedule = dueSchedule(
            oneShot = true,
            intervalSchedule = IntervalSchedule(
                startTimestamp = Instant.now().minus(1, ChronoUnit.HOURS),
                interval = "1h",
            ),
        )
        every { scheduleRepository.findDueSchedules(any()) } returns listOf(schedule)
        val savedSlot = slot<Schedule>()
        every { scheduleRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

        executor.poll()

        savedSlot.captured.enabled shouldBe false
    }

    // ------------------------------------------------------------------
    // fire — triggerAt schedule is disabled after trigger
    // ------------------------------------------------------------------

    "fire disables a triggerAt schedule after it fires" {
        val schedule = dueSchedule(triggerAt = Instant.now().minusSeconds(60))
        every { scheduleRepository.findDueSchedules(any()) } returns listOf(schedule)
        val savedSlot = slot<Schedule>()
        every { scheduleRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

        executor.poll()

        savedSlot.captured.enabled shouldBe false
        savedSlot.captured.nextTriggerAt.shouldBeNull()
    }

    // ------------------------------------------------------------------
    // fire — recurring schedule keeps nextTriggerAt after trigger
    // ------------------------------------------------------------------

    "fire updates nextTriggerAt for an open-ended recurring schedule" {
        val interval = IntervalSchedule(
            startTimestamp = Instant.now().minus(1, ChronoUnit.HOURS),
            interval = "1h",
        )
        val schedule = dueSchedule(intervalSchedule = interval)
        every { scheduleRepository.findDueSchedules(any()) } returns listOf(schedule)
        val savedSlot = slot<Schedule>()
        every { scheduleRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

        executor.poll()

        val saved = savedSlot.captured
        saved.enabled shouldBe true
        saved.occurrenceCount shouldBe 1
        // nextTriggerAt must be approximately now + 1h (the executor passes Instant.now() as lastTrigger)
        saved.nextTriggerAt.shouldNotBeNull()
        val expectedMin = Instant.now().plus(59, ChronoUnit.MINUTES)
        val expectedMax = Instant.now().plus(61, ChronoUnit.MINUTES)
        val next = saved.nextTriggerAt
        (next.isAfter(expectedMin) && next.isBefore(expectedMax)) shouldBe true
    }

    // ------------------------------------------------------------------
    // fire — recurring schedule with Occurrences end condition exhausted
    // ------------------------------------------------------------------

    "fire disables a recurring schedule when Occurrences end condition is reached" {
        val interval = IntervalSchedule(
            startTimestamp = Instant.now().minus(1, ChronoUnit.HOURS),
            interval = "1h",
            endCondition = EndCondition.Occurrences(count = 3),
        )
        // occurrenceCount is already 2; after firing it becomes 3 == limit
        val schedule = dueSchedule(intervalSchedule = interval, occurrenceCount = 2)
        every { scheduleRepository.findDueSchedules(any()) } returns listOf(schedule)
        val savedSlot = slot<Schedule>()
        every { scheduleRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

        executor.poll()

        val saved = savedSlot.captured
        saved.enabled shouldBe false
        saved.occurrenceCount shouldBe 3
    }

    // ------------------------------------------------------------------
    // fire — tracking fields are updated
    // ------------------------------------------------------------------

    "fire increments occurrenceCount and sets lastTriggeredAt" {
        val interval = IntervalSchedule(
            startTimestamp = Instant.now().minus(2, ChronoUnit.HOURS),
            interval = "1h",
        )
        val schedule = dueSchedule(intervalSchedule = interval, occurrenceCount = 5)
        every { scheduleRepository.findDueSchedules(any()) } returns listOf(schedule)
        val savedSlot = slot<Schedule>()
        every { scheduleRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

        executor.poll()

        savedSlot.captured.occurrenceCount shouldBe 6
        savedSlot.captured.lastTriggeredAt.shouldNotBeNull()
    }

    // ------------------------------------------------------------------
    // resolveOrCreateCase — uses existing caseId when provided
    // ------------------------------------------------------------------

    "fire uses existing caseId when schedule has one" {
        val schedule = dueSchedule(
            caseIdOverride = caseId,
            intervalSchedule = IntervalSchedule(
                startTimestamp = Instant.now().minus(1, ChronoUnit.HOURS),
                interval = "1h",
            ),
        )
        every { scheduleRepository.findDueSchedules(any()) } returns listOf(schedule)
        every { scheduleRepository.save(any()) } answers { firstArg() }

        executor.poll()

        verify(exactly = 1) { caseService.addMessage(eq(caseId), any(), any(), any()) }
        verify(exactly = 0) { caseService.create(any()) }
    }

    // ------------------------------------------------------------------
    // resolveOrCreateCase — creates a new case when caseId is null
    // ------------------------------------------------------------------

    "fire creates a new case when schedule has no caseId" {
        val newCaseId = UUID.randomUUID()
        val newCase = Case(
            metadata = EntityMetadata(id = newCaseId),
            namespaceId = namespaceId,
        )
        val schedule = dueSchedule(
            caseIdOverride = null,
            intervalSchedule = IntervalSchedule(
                startTimestamp = Instant.now().minus(1, ChronoUnit.HOURS),
                interval = "1h",
            ),
        )
        every { scheduleRepository.findDueSchedules(any()) } returns listOf(schedule)
        every { scheduleRepository.save(any()) } answers { firstArg() }
        every { caseService.create(any()) } returns newCase

        executor.poll()

        verify(exactly = 1) { caseService.create(any()) }
        verify(exactly = 1) { caseService.addMessage(eq(newCaseId), any(), any(), any()) }
    }

    // ------------------------------------------------------------------
    // resolveActor — uses user details when userId is set
    // ------------------------------------------------------------------

    "fire uses actor with user display name when userId is set" {
        val userId = UUID.randomUUID()
        val user = User(
            metadata = EntityMetadata(id = userId),
            externalId = "alice@example.com",
            firstname = "Alice",
            lastname = "Smith",
        )
        every { userService.findById(userId) } returns user

        val schedule = dueSchedule(
            userId = userId,
            intervalSchedule = IntervalSchedule(
                startTimestamp = Instant.now().minus(1, ChronoUnit.HOURS),
                interval = "1h",
            ),
        )
        every { scheduleRepository.findDueSchedules(any()) } returns listOf(schedule)
        every { scheduleRepository.save(any()) } answers { firstArg() }

        val actorSlot = slot<io.whozoss.agentos.sdk.actor.Actor>()
        every { caseService.addMessage(any(), capture(actorSlot), any(), any()) } returns Unit

        executor.poll()

        actorSlot.captured.id shouldBe userId.toString()
        actorSlot.captured.displayName shouldBe "Alice Smith"
        actorSlot.captured.role shouldBe ActorRole.USER
    }

    // ------------------------------------------------------------------
    // resolveActor — falls back to synthetic actor when no userId
    // ------------------------------------------------------------------

    "fire uses synthetic scheduler actor when schedule has no userId" {
        val schedule = dueSchedule(
            userId = null,
            intervalSchedule = IntervalSchedule(
                startTimestamp = Instant.now().minus(1, ChronoUnit.HOURS),
                interval = "1h",
            ),
        )
        every { scheduleRepository.findDueSchedules(any()) } returns listOf(schedule)
        every { scheduleRepository.save(any()) } answers { firstArg() }

        val actorSlot = slot<io.whozoss.agentos.sdk.actor.Actor>()
        every { caseService.addMessage(any(), capture(actorSlot), any(), any()) } returns Unit

        executor.poll()

        actorSlot.captured.id shouldBe ScheduleExecutorService.SCHEDULER_ACTOR_ID
        actorSlot.captured.displayName shouldBe "Scheduler"
        actorSlot.captured.role shouldBe ActorRole.USER
    }

    // ------------------------------------------------------------------
    // catch-up policy — NONE skips all due schedules
    // ------------------------------------------------------------------

    "poll fires nothing when catch-up policy is NONE" {
        val noneConfig = SchedulerConfig(catchUpPolicy = CatchUpPolicy.NONE)
        val noneExecutor = ScheduleExecutorService(scheduleRepository, caseService, userService, noneConfig)

        val s1 = dueSchedule(nextTriggerAt = Instant.now().minus(2, ChronoUnit.HOURS))
        val s2 = dueSchedule(nextTriggerAt = Instant.now().minus(1, ChronoUnit.HOURS))
        every { scheduleRepository.findDueSchedules(any()) } returns listOf(s1, s2)

        noneExecutor.poll()

        verify(exactly = 0) { caseService.addMessage(any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------
    // error isolation — exception in one schedule does not abort others
    // ------------------------------------------------------------------

    "poll continues firing remaining schedules when one throws" {
        val badId = UUID.randomUUID()
        val goodId = UUID.randomUUID()
        val goodCaseId = UUID.randomUUID()

        val badSchedule = dueSchedule(
            id = badId,
            caseIdOverride = caseId,
            intervalSchedule = IntervalSchedule(
                startTimestamp = Instant.now().minus(1, ChronoUnit.HOURS),
                interval = "1h",
            ),
        )
        val goodSchedule = dueSchedule(
            id = goodId,
            caseIdOverride = goodCaseId,
            intervalSchedule = IntervalSchedule(
                startTimestamp = Instant.now().minus(1, ChronoUnit.HOURS),
                interval = "1h",
            ),
        )
        every { scheduleRepository.findDueSchedules(any()) } returns listOf(badSchedule, goodSchedule)
        every { scheduleRepository.save(any()) } answers { firstArg() }
        every { caseService.addMessage(eq(caseId), any(), any(), any()) } throws RuntimeException("boom")

        executor.poll()

        // The good schedule must still have fired
        verify(exactly = 1) { caseService.addMessage(eq(goodCaseId), any(), any(), any()) }
    }
})
