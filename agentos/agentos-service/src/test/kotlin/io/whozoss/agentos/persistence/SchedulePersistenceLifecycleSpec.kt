package io.whozoss.agentos.persistence

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.schedule.InMemoryScheduleRepository
import io.whozoss.agentos.schedule.Schedule
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.schedule.EndCondition
import io.whozoss.agentos.sdk.schedule.IntervalSchedule
import java.time.Instant
import java.util.UUID

/**
 * CRUD lifecycle contract tests for [Schedule] in-memory persistence.
 *
 * Covers: create, read, update, soft-delete, [deleteByParent], idempotent delete,
 * stable identity, and ordering by [nextTriggerAt].
 *
 * Restart-survival semantics are verified by Neo4j persistence specs.
 */
class SchedulePersistenceLifecycleSpec : StringSpec({

    fun schedule(
        namespaceId: UUID = UUID.randomUUID(),
        message: String = "wake up",
        nextTriggerAt: Instant? = null,
    ) = Schedule(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        message = message,
        nextTriggerAt = nextTriggerAt,
    )

    // =========================================================================
    // Full CRUD lifecycle
    // =========================================================================

    "full CRUD lifecycle: create, read, update, delete" {
        val repo = InMemoryScheduleRepository()
        val nsId = UUID.randomUUID()

        val s = schedule(namespaceId = nsId, message = "initial")
        val created = repo.save(s)
        created.message shouldBe "initial"

        val found = repo.findByIds(listOf(created.metadata.id))
        found shouldHaveSize 1
        found.first().message shouldBe "initial"

        val updated = created.copy(message = "updated")
        repo.save(updated)
        val afterUpdate = repo.findByIds(listOf(created.metadata.id)).first()
        afterUpdate.message shouldBe "updated"

        repo.delete(created.metadata.id).shouldBeTrue()
        repo.findByIds(listOf(created.metadata.id)).shouldBeEmpty()
    }

    // =========================================================================
    // findByParent scoping
    // =========================================================================

    "findByParent returns only schedules for the given namespace" {
        val repo = InMemoryScheduleRepository()
        val nsA = UUID.randomUUID()
        val nsB = UUID.randomUUID()

        repo.save(schedule(namespaceId = nsA, message = "a1"))
        repo.save(schedule(namespaceId = nsA, message = "a2"))
        repo.save(schedule(namespaceId = nsB, message = "b1"))

        repo.findByParent(nsA) shouldHaveSize 2
        repo.findByParent(nsB) shouldHaveSize 1
        repo.findByParent(UUID.randomUUID()).shouldBeEmpty()
    }

    // =========================================================================
    // Ordering by nextTriggerAt
    // =========================================================================

    "findByParent returns schedules ordered by nextTriggerAt nulls last" {
        val repo = InMemoryScheduleRepository()
        val nsId = UUID.randomUUID()

        val t1 = Instant.parse("2025-06-01T10:00:00Z")
        val t2 = Instant.parse("2025-07-01T10:00:00Z")

        val sNull = repo.save(schedule(namespaceId = nsId, message = "no trigger", nextTriggerAt = null))
        val sLater = repo.save(schedule(namespaceId = nsId, message = "later", nextTriggerAt = t2))
        val sSooner = repo.save(schedule(namespaceId = nsId, message = "sooner", nextTriggerAt = t1))

        val result = repo.findByParent(nsId)
        result.map { it.message } shouldBe listOf("sooner", "later", "no trigger")
    }

    // =========================================================================
    // Soft-delete idempotence
    // =========================================================================

    "deleting an already-deleted schedule returns false" {
        val repo = InMemoryScheduleRepository()
        val s = repo.save(schedule(message = "to-delete"))

        repo.delete(s.metadata.id).shouldBeTrue()
        repo.delete(s.metadata.id).shouldBeFalse()
    }

    // =========================================================================
    // deleteByParent cascade
    // =========================================================================

    "deleteByParent removes all schedules under the namespace" {
        val repo = InMemoryScheduleRepository()
        val nsId = UUID.randomUUID()

        repo.save(schedule(namespaceId = nsId, message = "s1"))
        repo.save(schedule(namespaceId = nsId, message = "s2"))
        repo.save(schedule(namespaceId = nsId, message = "s3"))

        val deleted = repo.deleteByParent(nsId)
        deleted shouldBe 3
        repo.findByParent(nsId).shouldBeEmpty()
    }

    "deleteByParent does not affect schedules in other namespaces" {
        val repo = InMemoryScheduleRepository()
        val nsA = UUID.randomUUID()
        val nsB = UUID.randomUUID()

        repo.save(schedule(namespaceId = nsA, message = "a"))
        val survivor = repo.save(schedule(namespaceId = nsB, message = "b"))

        repo.deleteByParent(nsA)

        repo.findByParent(nsA).shouldBeEmpty()
        val remaining = repo.findByParent(nsB)
        remaining shouldHaveSize 1
        remaining.first().metadata.id shouldBe survivor.metadata.id
    }

    // =========================================================================
    // Stable identity
    // =========================================================================

    "schedule id is stable after creation" {
        val repo = InMemoryScheduleRepository()
        val fixedId = UUID.randomUUID()
        val nsId = UUID.randomUUID()
        repo.save(Schedule(metadata = EntityMetadata(id = fixedId), namespaceId = nsId, message = "stable"))

        val found = repo.findByIds(listOf(fixedId)).first()
        found.metadata.id shouldBe fixedId
        found.message shouldBe "stable"
    }

    // =========================================================================
    // IntervalSchedule round-trip
    // =========================================================================

    "intervalSchedule is preserved through save and read" {
        val repo = InMemoryScheduleRepository()
        val nsId = UUID.randomUUID()
        val interval = IntervalSchedule(
            startTimestamp = Instant.parse("2025-01-06T10:00:00Z"),
            interval = "14d",
            daysOfWeek = listOf(1, 5),
            endCondition = EndCondition.Occurrences(26),
        )
        val saved = repo.save(
            Schedule(
                metadata = EntityMetadata(),
                namespaceId = nsId,
                message = "bi-weekly on Mon/Fri",
                intervalSchedule = interval,
            )
        )

        val found = repo.findByIds(listOf(saved.metadata.id)).first()
        found.intervalSchedule shouldBe interval
    }
})
