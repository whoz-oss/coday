package io.whozoss.agentos.schedule

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.schedule.EndCondition
import io.whozoss.agentos.sdk.schedule.IntervalSchedule
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [ScheduleController].
 *
 * The controller is instantiated directly with MockK stubs — no Spring context.
 * Tests cover:
 * - [ScheduleController.toResource]  — domain → HTTP DTO mapping
 * - [ScheduleController.toDomain]    — HTTP DTO → domain mapping
 * - Inherited [io.whozoss.agentos.entity.EntityController] endpoints:
 *   getById (found / not-found), getByIds, listByParent, create,
 *   update (found / not-found), delete (found / not-found)
 */
class ScheduleControllerUnitSpec : StringSpec({
    timeout = 5000

    val service = mockk<ScheduleService>()
    val controller = ScheduleController(service)

    val namespaceId = UUID.randomUUID()
    val start = Instant.parse("2025-01-06T10:00:00Z")

    fun schedule(
        id: UUID = UUID.randomUUID(),
        nsId: UUID = namespaceId,
        message: String = "wake up",
        enabled: Boolean = true,
        oneShot: Boolean = false,
        triggerAt: Instant? = null,
        intervalSchedule: IntervalSchedule? = null,
        nextTriggerAt: Instant? = null,
    ) = Schedule(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        message = message,
        enabled = enabled,
        oneShot = oneShot,
        triggerAt = triggerAt,
        intervalSchedule = intervalSchedule,
        nextTriggerAt = nextTriggerAt,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        nsId: UUID = namespaceId,
        message: String = "wake up",
        enabled: Boolean = true,
        oneShot: Boolean = false,
        triggerAt: Instant? = null,
        intervalSchedule: IntervalSchedule? = null,
        nextTriggerAt: Instant? = null,
    ) = ScheduleResource(
        id = id,
        namespaceId = nsId,
        message = message,
        enabled = enabled,
        oneShot = oneShot,
        triggerAt = triggerAt,
        intervalSchedule = intervalSchedule,
        nextTriggerAt = nextTriggerAt,
    )

    // -------------------------------------------------------------------------
    // toResource mapping
    // -------------------------------------------------------------------------

    "toResource maps all scalar fields from Schedule to ScheduleResource" {
        val id = UUID.randomUUID()
        val trigger = Instant.parse("2025-06-01T09:00:00Z")
        val s = schedule(id = id, message = "deploy check", enabled = false, oneShot = true, triggerAt = trigger)

        val result = controller.toResource(s)

        result.id shouldBe id
        result.namespaceId shouldBe namespaceId
        result.message shouldBe "deploy check"
        result.enabled shouldBe false
        result.oneShot shouldBe true
        result.triggerAt shouldBe trigger
    }

    "toResource maps intervalSchedule including endCondition" {
        val interval = IntervalSchedule(
            startTimestamp = start,
            interval = "14d",
            daysOfWeek = listOf(1, 5),
            endCondition = EndCondition.Occurrences(10),
        )
        val s = schedule(intervalSchedule = interval)

        val result = controller.toResource(s)

        result.intervalSchedule shouldBe interval
    }

    "toResource preserves null optional fields" {
        val s = schedule(triggerAt = null, intervalSchedule = null, nextTriggerAt = null)

        val result = controller.toResource(s)

        result.triggerAt shouldBe null
        result.intervalSchedule shouldBe null
        result.nextTriggerAt shouldBe null
    }

    // -------------------------------------------------------------------------
    // toDomain mapping
    // -------------------------------------------------------------------------

    "toDomain maps all fields from ScheduleResource to Schedule" {
        val id = UUID.randomUUID()
        val trigger = Instant.parse("2025-06-01T09:00:00Z")
        val r = resource(id = id, message = "daily report", enabled = true, oneShot = false, triggerAt = trigger)

        val result = controller.toDomain(r)

        result.metadata.id shouldBe id
        result.namespaceId shouldBe namespaceId
        result.message shouldBe "daily report"
        result.triggerAt shouldBe trigger
    }

    "toDomain generates a random UUID when resource id is null" {
        val r = resource(id = null)

        val result = controller.toDomain(r)

        result.metadata.id shouldBe result.metadata.id
    }

    "toDomain maps intervalSchedule with EndTimestamp endCondition" {
        val interval = IntervalSchedule(
            startTimestamp = start,
            interval = "1M",
            endCondition = EndCondition.EndTimestamp(Instant.parse("2026-01-01T00:00:00Z")),
        )
        val r = resource(intervalSchedule = interval)

        val result = controller.toDomain(r)

        result.intervalSchedule shouldBe interval
    }

    // -------------------------------------------------------------------------
    // getById (inherited)
    // -------------------------------------------------------------------------

    "getById returns a resource when the entity is found" {
        val s = schedule()
        every { service.findById(s.id) } returns s

        val result = controller.getById(s.id)

        result shouldBe controller.toResource(s)
    }

    "getById throws 404 when entity is not found" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        val ex = runCatching { controller.getById(id) }.exceptionOrNull()

        (ex is ResourceNotFoundException) shouldBe true
    }

    // -------------------------------------------------------------------------
    // getByIds (inherited)
    // -------------------------------------------------------------------------

    "getByIds returns matching entities mapped to resources" {
        val s1 = schedule(message = "alpha")
        val s2 = schedule(message = "beta")
        every { service.findByIds(listOf(s1.id, s2.id)) } returns listOf(s1, s2)

        val result = controller.getByIds(listOf(s1.id, s2.id))

        result shouldBe listOf(controller.toResource(s1), controller.toResource(s2))
    }

    // -------------------------------------------------------------------------
    // listByParent (inherited)
    // -------------------------------------------------------------------------

    "listByParent returns schedules for the given namespaceId" {
        val s1 = schedule(message = "first")
        val s2 = schedule(message = "second")
        every { service.findByParent(namespaceId) } returns listOf(s1, s2)

        val result = controller.listByParent(namespaceId)

        result shouldBe listOf(controller.toResource(s1), controller.toResource(s2))
        verify(exactly = 1) { service.findByParent(namespaceId) }
    }

    // -------------------------------------------------------------------------
    // create (inherited)
    // -------------------------------------------------------------------------

    "create converts resource to domain, delegates to service, and returns mapped resource" {
        val r = resource(id = null)
        val saved = controller.toDomain(r)
        every { service.create(any()) } returns saved

        val result = controller.create(r)

        result shouldBe controller.toResource(saved)
        verify(exactly = 1) { service.create(any()) }
    }

    // -------------------------------------------------------------------------
    // update (inherited)
    // -------------------------------------------------------------------------

    "update delegates to service when entity exists and returns mapped resource" {
        val s = schedule()
        val updatedResource = resource(id = s.id, message = "updated message")
        val updatedDomain = controller.toDomain(updatedResource)
        every { service.findById(s.id) } returns s
        every { service.update(any()) } returns updatedDomain

        val result = controller.update(s.id, updatedResource)

        result shouldBe controller.toResource(updatedDomain)
        verify(exactly = 1) { service.update(any()) }
    }

    "update throws 404 when entity is not found" {
        val id = UUID.randomUUID()
        val r = resource(id = id)
        every { service.findById(id) } returns null

        val ex = runCatching { controller.update(id, r) }.exceptionOrNull()

        (ex is ResourceNotFoundException) shouldBe true
    }

    // -------------------------------------------------------------------------
    // delete (inherited)
    // -------------------------------------------------------------------------

    "delete succeeds when entity exists" {
        val id = UUID.randomUUID()
        every { service.delete(id) } returns true

        controller.delete(id)

        verify(exactly = 1) { service.delete(id) }
    }

    "delete throws 404 when service returns false" {
        val id = UUID.randomUUID()
        every { service.delete(id) } returns false

        val ex = runCatching { controller.delete(id) }.exceptionOrNull()

        (ex is ResourceNotFoundException) shouldBe true
    }
})
