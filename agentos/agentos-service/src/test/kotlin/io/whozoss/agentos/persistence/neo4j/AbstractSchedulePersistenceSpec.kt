package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.schedule.Schedule
import io.whozoss.agentos.schedule.ScheduleRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.schedule.EndCondition
import io.whozoss.agentos.sdk.schedule.IntervalSchedule
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

/**
 * Shared persistence contract tests for [ScheduleRepository] / [Neo4jScheduleRepository].
 *
 * Subclasses activate a specific persistence mode (embedded harness or Testcontainers)
 * and inherit all test cases, ensuring both modes satisfy the same contract.
 */
abstract class AbstractSchedulePersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var repo: ScheduleRepository

    @Autowired
    lateinit var driver: Driver

    private fun schedule(
        namespaceId: UUID = UUID.randomUUID(),
        message: String = "wake up",
        nextTriggerAt: Instant? = null,
    ) = Schedule(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        message = message,
        nextTriggerAt = nextTriggerAt,
    )

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        // -------------------------------------------------------------------------
        // Basic save / read
        // -------------------------------------------------------------------------

        "save and findByIds returns the same schedule" {
            val nsId = UUID.randomUUID()
            val saved = repo.save(schedule(namespaceId = nsId, message = "check deployment"))

            val found = repo.findByIds(listOf(saved.id))

            found shouldHaveSize 1
            found.first().id shouldBe saved.id
            found.first().message shouldBe "check deployment"
        }

        // -------------------------------------------------------------------------
        // findByParent
        // -------------------------------------------------------------------------

        "findByParent returns only schedules for the given namespaceId" {
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()
            repo.save(schedule(namespaceId = nsA, message = "a1"))
            repo.save(schedule(namespaceId = nsA, message = "a2"))
            repo.save(schedule(namespaceId = nsB, message = "b1"))

            repo.findByParent(nsA) shouldHaveSize 2
            repo.findByParent(nsB) shouldHaveSize 1
            repo.findByParent(UUID.randomUUID()).shouldBeEmpty()
        }

        "findByParent returns schedules ordered by nextTriggerAt nulls last" {
            val nsId = UUID.randomUUID()
            val t1 = Instant.parse("2025-06-01T10:00:00Z")
            val t2 = Instant.parse("2025-07-01T10:00:00Z")

            repo.save(schedule(namespaceId = nsId, message = "no trigger", nextTriggerAt = null))
            repo.save(schedule(namespaceId = nsId, message = "later", nextTriggerAt = t2))
            repo.save(schedule(namespaceId = nsId, message = "sooner", nextTriggerAt = t1))

            val result = repo.findByParent(nsId)
            result.map { it.message } shouldBe listOf("sooner", "later", "no trigger")
        }

        // -------------------------------------------------------------------------
        // Soft delete
        // -------------------------------------------------------------------------

        "soft delete removes schedule from findByIds" {
            val saved = repo.save(schedule(message = "to-delete"))

            repo.delete(saved.id).shouldBeTrue()
            repo.findByIds(listOf(saved.id)).shouldBeEmpty()
        }

        "double delete returns false" {
            val saved = repo.save(schedule(message = "double"))

            repo.delete(saved.id).shouldBeTrue()
            repo.delete(saved.id).shouldBeFalse()
        }

        // -------------------------------------------------------------------------
        // deleteByParent
        // -------------------------------------------------------------------------

        "deleteByParent removes all schedules for the namespace" {
            val nsId = UUID.randomUUID()
            repo.save(schedule(namespaceId = nsId, message = "s1"))
            repo.save(schedule(namespaceId = nsId, message = "s2"))

            val deleted = repo.deleteByParent(nsId)
            deleted shouldBe 2
            repo.findByParent(nsId).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Update
        // -------------------------------------------------------------------------

        "update: saving with same id replaces the node" {
            val saved = repo.save(schedule(message = "original"))

            repo.save(saved.copy(message = "updated"))

            val found = repo.findByIds(listOf(saved.id))
            found shouldHaveSize 1
            found.first().message shouldBe "updated"
        }

        // -------------------------------------------------------------------------
        // IntervalSchedule JSON round-trip
        // -------------------------------------------------------------------------

        "intervalSchedule with daysOfWeek and Occurrences endCondition round-trips through Neo4j" {
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
                    message = "bi-weekly",
                    intervalSchedule = interval,
                )
            )

            val found = repo.findByIds(listOf(saved.id)).first()

            found.intervalSchedule shouldBe interval
        }

        "intervalSchedule with EndTimestamp endCondition round-trips through Neo4j" {
            val nsId = UUID.randomUUID()
            val interval = IntervalSchedule(
                startTimestamp = Instant.parse("2025-01-01T00:00:00Z"),
                interval = "1M",
                endCondition = EndCondition.EndTimestamp(Instant.parse("2025-12-31T23:59:59Z")),
            )
            val saved = repo.save(
                Schedule(
                    metadata = EntityMetadata(),
                    namespaceId = nsId,
                    message = "monthly until end of year",
                    intervalSchedule = interval,
                )
            )

            val found = repo.findByIds(listOf(saved.id)).first()

            found.intervalSchedule shouldBe interval
        }

        "null intervalSchedule is preserved" {
            val saved = repo.save(schedule(message = "one-shot"))

            val found = repo.findByIds(listOf(saved.id)).first()

            found.intervalSchedule shouldBe null
        }
    }
}
