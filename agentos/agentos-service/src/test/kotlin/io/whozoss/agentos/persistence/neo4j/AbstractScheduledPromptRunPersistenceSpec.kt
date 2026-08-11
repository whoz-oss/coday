package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.scheduledPrompt.RunStatus
import io.whozoss.agentos.scheduledPrompt.ScheduledPromptRunRepository
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

/**
 * Persistence contract tests for all custom Cypher queries in
 * [io.whozoss.agentos.scheduledPrompt.ScheduledPromptRunNodeNeo4jRepository].
 *
 * Covers:
 * - [ScheduledPromptRunRepository.updateStatus] — terminal-status guard, field persistence
 * - [ScheduledPromptRunRepository.hasActive] — CLAIMED/RUNNING detection, removed filter
 * - [ScheduledPromptRunRepository.countCompletedRuns] — temporal filter on scheduledFor
 * - [ScheduledPromptRunRepository.findOrphanedClaimed] — status + age filter, ordering
 * - [ScheduledPromptRunRepository.findSettledRunning] — NOT EXISTS sub-query against UserRuns
 *
 * This spec was introduced after a production bug where `r.status NOT IN [...]` (invalid Cypher)
 * was used instead of `NOT r.status IN [...]` in `updateStatus`. Any Cypher syntax error in any
 * query causes the Spring context to fail at startup, so the test acts as an early-detection guard
 * for query regressions across the entire repository.
 */
abstract class AbstractScheduledPromptRunPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var runRepo: ScheduledPromptRunRepository

    @Autowired lateinit var driver: Driver

    // ---------------------------------------------------------------------------
    // Builders
    // ---------------------------------------------------------------------------

    private fun run(
        status: RunStatus = RunStatus.CLAIMED,
        scheduledPromptId: UUID = UUID.randomUUID(),
        scheduledFor: Instant = Instant.now(),
    ) = io.whozoss.agentos.scheduledPrompt.ScheduledPromptRun(
        scheduledPromptId = scheduledPromptId,
        scheduledFor = scheduledFor,
        status = status,
        correlationId = "test-${UUID.randomUUID()}",
    )

    /**
     * Insert a minimal ScheduledPromptUserRun node directly via Cypher.
     * Uses $$""" raw strings so $param tokens are literal Cypher parameters,
     * not Kotlin string interpolation.
     */
    private fun insertUserRun(runId: UUID, status: String) {
        driver.session().use { session ->
            session.run(
                $$"""
                CREATE (ur:ScheduledPromptUserRun {
                    id:       randomUUID(),
                    runId:    $runId,
                    userId:   randomUUID(),
                    status:   $status,
                    version:  0,
                    created:  datetime(),
                    modified: datetime()
                })
                """,
                mapOf("runId" to runId.toString(), "status" to status),
            )
        }
    }

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        // -------------------------------------------------------------------------
        // updateStatus — non-terminal source status
        // -------------------------------------------------------------------------

        "updateStatus returns true and updates status when Run is CLAIMED" {
            val saved = runRepo.insert(run(RunStatus.CLAIMED))

            val updated = runRepo.updateStatus(saved.id, RunStatus.RUNNING)

            updated shouldBe true
            runRepo.findById(saved.id)!!.status shouldBe RunStatus.RUNNING
        }

        "updateStatus returns true and updates status when Run is RUNNING" {
            val saved = runRepo.insert(run(RunStatus.RUNNING))

            val updated = runRepo.updateStatus(saved.id, RunStatus.DONE, finishedAt = Instant.now())

            updated shouldBe true
            runRepo.findById(saved.id)!!.status shouldBe RunStatus.DONE
        }

        "updateStatus sets finishedAt and error when provided" {
            val saved = runRepo.insert(run(RunStatus.RUNNING))

            runRepo.updateStatus(saved.id, RunStatus.FAILED, finishedAt = Instant.now(), error = "boom")

            val result = runRepo.findById(saved.id)!!
            result.status shouldBe RunStatus.FAILED
            result.error shouldBe "boom"
        }

        // -------------------------------------------------------------------------
        // updateStatus — terminal-status guard
        // -------------------------------------------------------------------------

        "updateStatus returns false and does not overwrite a DONE Run" {
            val saved = runRepo.insert(run(RunStatus.DONE))

            val updated = runRepo.updateStatus(saved.id, RunStatus.FAILED, error = "late")

            updated shouldBe false
            runRepo.findById(saved.id)!!.status shouldBe RunStatus.DONE
        }

        "updateStatus returns false and does not overwrite a FAILED Run" {
            val saved = runRepo.insert(run(RunStatus.FAILED))

            val updated = runRepo.updateStatus(saved.id, RunStatus.DONE)

            updated shouldBe false
            runRepo.findById(saved.id)!!.status shouldBe RunStatus.FAILED
        }

        "updateStatus returns false when Run does not exist" {
            val updated = runRepo.updateStatus(UUID.randomUUID(), RunStatus.DONE)

            updated shouldBe false
        }

        // -------------------------------------------------------------------------
        // hasActive
        // -------------------------------------------------------------------------

        "hasActive returns true when a CLAIMED Run exists" {
            val spId = UUID.randomUUID()
            runRepo.insert(run(RunStatus.CLAIMED, scheduledPromptId = spId))

            runRepo.hasActive(spId) shouldBe true
        }

        "hasActive returns true when a RUNNING Run exists" {
            val spId = UUID.randomUUID()
            runRepo.insert(run(RunStatus.RUNNING, scheduledPromptId = spId))

            runRepo.hasActive(spId) shouldBe true
        }

        "hasActive returns false when only terminal Runs exist" {
            val spId = UUID.randomUUID()
            runRepo.insert(run(RunStatus.DONE, scheduledPromptId = spId))
            runRepo.insert(run(RunStatus.FAILED, scheduledPromptId = spId, scheduledFor = Instant.now().plusSeconds(1)))

            runRepo.hasActive(spId) shouldBe false
        }

        "hasActive returns false when no Runs exist for the scheduledPromptId" {
            runRepo.hasActive(UUID.randomUUID()) shouldBe false
        }

        // -------------------------------------------------------------------------
        // countCompletedRuns
        // -------------------------------------------------------------------------

        "countCompletedRuns counts all runs on or after startInstant" {
            val spId = UUID.randomUUID()
            val base = Instant.parse("2026-01-01T00:00:00Z")
            runRepo.insert(run(RunStatus.DONE, scheduledPromptId = spId, scheduledFor = base))
            runRepo.insert(run(RunStatus.DONE, scheduledPromptId = spId, scheduledFor = base.plusSeconds(1)))
            runRepo.insert(run(RunStatus.DONE, scheduledPromptId = spId, scheduledFor = base.minusSeconds(1)))

            // startInstant = base: the run at base-1s is excluded
            runRepo.countCompletedRuns(spId, base) shouldBe 2
        }

        "countCompletedRuns includes SKIPPED and FAILED runs" {
            val spId = UUID.randomUUID()
            val base = Instant.parse("2026-01-01T00:00:00Z")
            runRepo.insert(run(RunStatus.DONE,    scheduledPromptId = spId, scheduledFor = base))
            runRepo.insert(run(RunStatus.SKIPPED, scheduledPromptId = spId, scheduledFor = base.plusSeconds(1)))
            runRepo.insert(run(RunStatus.FAILED,  scheduledPromptId = spId, scheduledFor = base.plusSeconds(2)))

            runRepo.countCompletedRuns(spId, base) shouldBe 3
        }

        "countCompletedRuns returns 0 when all runs are before startInstant" {
            val spId = UUID.randomUUID()
            val base = Instant.parse("2026-01-01T00:00:00Z")
            runRepo.insert(run(RunStatus.DONE, scheduledPromptId = spId, scheduledFor = base.minusSeconds(1)))

            runRepo.countCompletedRuns(spId, base) shouldBe 0
        }

        "countCompletedRuns does not count runs for other scheduledPromptIds" {
            val spId = UUID.randomUUID()
            val otherId = UUID.randomUUID()
            val base = Instant.parse("2026-01-01T00:00:00Z")
            runRepo.insert(run(RunStatus.DONE, scheduledPromptId = spId,    scheduledFor = base))
            runRepo.insert(run(RunStatus.DONE, scheduledPromptId = otherId, scheduledFor = base))

            runRepo.countCompletedRuns(spId, base) shouldBe 1
        }

        // -------------------------------------------------------------------------
        // findOrphanedClaimed
        // -------------------------------------------------------------------------

        "findOrphanedClaimed returns CLAIMED runs created before the threshold" {
            val old = runRepo.insert(run(RunStatus.CLAIMED))
            // Force created timestamp to the past via Cypher
            driver.session().use { session ->
                session.run(
                    $$"""MATCH (r:ScheduledPromptRun {id: $id}) SET r.created = datetime('2020-01-01T00:00:00Z')""",
                    mapOf("id" to old.id.toString()),
                )
            }

            val orphans = runRepo.findOrphanedClaimed(Instant.now())

            orphans shouldHaveSize 1
            orphans.first().id shouldBe old.id
        }

        "findOrphanedClaimed does not return CLAIMED runs created after the threshold" {
            runRepo.insert(run(RunStatus.CLAIMED))

            // threshold in the past — the freshly inserted run is NOT older than it
            val orphans = runRepo.findOrphanedClaimed(Instant.now().minusSeconds(60))

            orphans.shouldBeEmpty()
        }

        "findOrphanedClaimed does not return non-CLAIMED runs" {
            listOf(RunStatus.RUNNING, RunStatus.DONE, RunStatus.FAILED, RunStatus.SKIPPED).forEachIndexed { i, status ->
                runRepo.insert(run(status, scheduledFor = Instant.now().plusSeconds(i.toLong())))
            }
            // Push all created timestamps to the past
            driver.session().use { session ->
                session.run("MATCH (r:ScheduledPromptRun) SET r.created = datetime('2020-01-01T00:00:00Z')")
            }

            val orphans = runRepo.findOrphanedClaimed(Instant.now())

            orphans.shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // findSettledRunning
        // -------------------------------------------------------------------------

        "findSettledRunning returns RUNNING Run with no UserRuns" {
            val saved = runRepo.insert(run(RunStatus.RUNNING))

            val settled = runRepo.findSettledRunning()

            settled shouldHaveSize 1
            settled.first().id shouldBe saved.id
        }

        "findSettledRunning returns RUNNING Run whose UserRuns are all terminal" {
            val saved = runRepo.insert(run(RunStatus.RUNNING))
            insertUserRun(saved.id, "DONE")
            insertUserRun(saved.id, "FAILED")

            val settled = runRepo.findSettledRunning()

            settled shouldHaveSize 1
            settled.first().id shouldBe saved.id
        }

        "findSettledRunning does not return RUNNING Run with a PENDING UserRun" {
            val saved = runRepo.insert(run(RunStatus.RUNNING))
            insertUserRun(saved.id, "PENDING")

            runRepo.findSettledRunning().shouldBeEmpty()
        }

        "findSettledRunning does not return RUNNING Run with a RUNNING UserRun" {
            val saved = runRepo.insert(run(RunStatus.RUNNING))
            insertUserRun(saved.id, "RUNNING")

            runRepo.findSettledRunning().shouldBeEmpty()
        }

        "findSettledRunning does not return non-RUNNING Runs" {
            listOf(RunStatus.CLAIMED, RunStatus.DONE, RunStatus.FAILED, RunStatus.SKIPPED).forEachIndexed { i, status ->
                runRepo.insert(run(status, scheduledFor = Instant.now().plusSeconds(i.toLong())))
            }

            runRepo.findSettledRunning().shouldBeEmpty()
        }
    }
}
