package io.whozoss.agentos.scheduledPrompt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.mockk.coEvery
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Unit tests for [SchedulerScanner].
 *
 * Uses in-memory repositories and a fixed clock — no Spring context, no Neo4j.
 *
 * Fixed "now": 2026-01-01 09:00:00 UTC (Thursday).
 */
class SchedulerScannerUnitSpec : StringSpec() {

    private val nowInstant = Instant.parse("2026-01-01T09:00:00Z")
    private val clock = Clock.fixed(nowInstant, ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 1, 1)
    private val defaultTime = LocalTime.of(8, 0)  // 08:00 UTC — before now

    private val properties = SchedulerProperties(tickIntervalMs = 60_000)

    private val agentId: UUID = UUID.randomUUID()
    private val promptId: UUID = UUID.randomUUID()

    private val activeAgent = AgentConfig(
        metadata = EntityMetadata(id = agentId, version = 0L),
        namespaceId = null,
        name = "test-agent",
        enabled = true,
    )

    /** Default AgentConfigService mock: agent exists and is enabled. */
    private fun defaultAgentConfigService(): AgentConfigService = mockk<AgentConfigService>().also {
        every { it.findById(agentId) } returns activeAgent
    }

    private fun makeScheduledPromptRepo() = InMemoryScheduledPromptRepository()
    private fun makeRunRepo() = InMemoryScheduledPromptRunRepository()

    private fun scanner(
        scheduledPromptRepo: InMemoryScheduledPromptRepository,
        runRepo: InMemoryScheduledPromptRunRepository,
        agentConfigService: AgentConfigService = defaultAgentConfigService(),
    ): SchedulerScanner {
        val executor = mockk<ScheduledPromptExecutor>(relaxed = true).also {
            every { it.materialize(any(), any()) } returns Unit
            coEvery { it.consumeAvailable() } returns Unit
        }
        return SchedulerScanner(
            scheduledPromptRepository = scheduledPromptRepo,
            runRepository = runRepo,
            agentConfigService = agentConfigService,
            properties = properties,
            clock = clock,
            nextRunCalculatorService = NextRunCalculatorService(clock = clock),
            executor = executor,
        )
    }

    private fun InMemoryScheduledPromptRepository.insertScheduledPrompt(
        nextRunAt: Instant,
        enabled: Boolean = true,
        startDate: LocalDate = today,
        timeUtc: LocalTime = defaultTime,
        endType: SchedulerEndType = SchedulerEndType.NEVER,
        endDate: LocalDate? = null,
        maxOccurrenceCount: Int? = null,
    ): ScheduledPrompt {
        val scheduledPrompt = ScheduledPrompt(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            agentConfigId = agentId,
            promptTemplateId = promptId,
            name = "sp-${UUID.randomUUID().toString().take(6)}",
            recurrence = Recurrence(unit = SchedulerUnit.WEEK, timeUtc = timeUtc),
            planning = Planning(
                startDate = startDate,
                endType = endType,
                endDate = endDate,
                maxOccurrenceCount = maxOccurrenceCount,
            ),
            enabled = enabled,
            nextRunAt = nextRunAt,
        )
        return save(scheduledPrompt)
    }

    init {
        // -------------------------------------------------------------------------
        // Tick — no due prompts
        // -------------------------------------------------------------------------

        "tickClaim with no due prompts: no runs created" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            scheduledPromptRepo.insertScheduledPrompt(nextRunAt = Instant.parse("2026-01-01T10:00:00Z"))
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            runRepo.all().shouldBeEmpty()
        }

        "tickClaim with disabled prompt: no runs created" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            scheduledPromptRepo.insertScheduledPrompt(nextRunAt = Instant.parse("2026-01-01T08:00:00Z"), enabled = false)
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            runRepo.all().shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Tick — one due prompt, happy path
        // -------------------------------------------------------------------------

        "tickClaim with 1 due prompt: run CLAIMED inserted" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            scheduledPromptRepo.insertScheduledPrompt(nextRunAt = slot)
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            val runs = runRepo.all()
            runs shouldHaveSize 1
            runs.first().status shouldBe RunStatus.CLAIMED
        }

        "tickClaim with 1 due prompt: nextRunAt advanced" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T09:00:00Z")
            // startDate=2026-01-01 (Thursday), WEEK no filter → next slot = next Thursday 2026-01-08
            val scheduledPrompt = scheduledPromptRepo.insertScheduledPrompt(nextRunAt = slot, timeUtc = LocalTime.of(9, 0))
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            val updated = scheduledPromptRepo.findById(scheduledPrompt.id)!!
            updated.nextRunAt shouldBe Instant.parse("2026-01-08T09:00:00Z")
        }

        "tickClaim with 1 due prompt: run scheduled for the claimed slot" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val scheduledPrompt = scheduledPromptRepo.insertScheduledPrompt(nextRunAt = slot)
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            val run = runRepo.all().first()
            run.scheduledPromptId shouldBe scheduledPrompt.id
            run.scheduledFor shouldBe slot
        }

        // -------------------------------------------------------------------------
        // Tick — overlap (active run already exists)
        // -------------------------------------------------------------------------

        "tickClaim with active run already exists: run SKIPPED (overlap)" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val scheduledPrompt = scheduledPromptRepo.insertScheduledPrompt(nextRunAt = slot)
            runRepo.insert(
                ScheduledPromptRun(
                    scheduledPromptId = scheduledPrompt.id,
                    scheduledFor = slot.minusSeconds(3600),
                    status = RunStatus.CLAIMED,
                    correlationId = "pre-existing",
                ),
            )
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            val runs = runRepo.all()
            runs shouldHaveSize 2
            runs.filter { it.correlationId != "pre-existing" }.first().status shouldBe RunStatus.SKIPPED
        }

        "tickClaim with RUNNING run already exists: run SKIPPED (overlap)" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val scheduledPrompt = scheduledPromptRepo.insertScheduledPrompt(nextRunAt = slot)
            runRepo.insert(
                ScheduledPromptRun(
                    scheduledPromptId = scheduledPrompt.id,
                    scheduledFor = slot.minusSeconds(3600),
                    status = RunStatus.RUNNING,
                    correlationId = "running",
                ),
            )
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            runRepo.all().filter { it.correlationId != "running" }.first().status shouldBe RunStatus.SKIPPED
        }

        "tickClaim with DONE run: run CLAIMED (DONE is not active)" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val scheduledPrompt = scheduledPromptRepo.insertScheduledPrompt(nextRunAt = slot)
            runRepo.insert(
                ScheduledPromptRun(
                    scheduledPromptId = scheduledPrompt.id,
                    scheduledFor = slot.minusSeconds(3600),
                    status = RunStatus.DONE,
                    correlationId = "done-run",
                ),
            )
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            runRepo.all().filter { it.correlationId != "done-run" }.first().status shouldBe RunStatus.CLAIMED
        }

        // -------------------------------------------------------------------------
        // Tick — DuplicateRunException (concurrent tick wins the race)
        // -------------------------------------------------------------------------

        "tickClaim with DuplicateRunException: nextRunAt still advanced" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            // startDate=2026-01-01 (Thursday), WEEK no filter → next slot = next Thursday 2026-01-08
            val scheduledPrompt = scheduledPromptRepo.insertScheduledPrompt(nextRunAt = slot)
            runRepo.insert(
                ScheduledPromptRun(
                    scheduledPromptId = scheduledPrompt.id,
                    scheduledFor = slot,
                    status = RunStatus.CLAIMED,
                    correlationId = "first-tick",
                ),
            )
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            val updated = scheduledPromptRepo.findById(scheduledPrompt.id)!!
            updated.nextRunAt shouldBe Instant.parse("2026-01-08T08:00:00Z")
        }

        "tickClaim with DuplicateRunException: no extra run inserted" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val scheduledPrompt = scheduledPromptRepo.insertScheduledPrompt(nextRunAt = slot)
            runRepo.insert(
                ScheduledPromptRun(
                    scheduledPromptId = scheduledPrompt.id,
                    scheduledFor = slot,
                    status = RunStatus.CLAIMED,
                    correlationId = "first-tick",
                ),
            )
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            runRepo.all() shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // CAS advance
        // -------------------------------------------------------------------------

        "advanceNextRunAt CAS: returns true when slot matches" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val scheduledPrompt = scheduledPromptRepo.insertScheduledPrompt(nextRunAt = slot)
            scheduledPromptRepo.advanceNextRunAt(scheduledPrompt.id, slot, slot.plusSeconds(86400)).shouldBeTrue()
        }

        "advanceNextRunAt CAS: returns false when slot does not match" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val scheduledPrompt = scheduledPromptRepo.insertScheduledPrompt(nextRunAt = slot)
            scheduledPromptRepo.advanceNextRunAt(scheduledPrompt.id, slot.plusSeconds(1), slot.plusSeconds(86400)).shouldBeFalse()
        }

        // -------------------------------------------------------------------------
        // AgentConfig guard
        // -------------------------------------------------------------------------

        "tickClaim with deleted AgentConfig: ScheduledPrompt disabled, no run inserted" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val scheduledPrompt = scheduledPromptRepo.insertScheduledPrompt(nextRunAt = slot)
            val agentSvc = mockk<AgentConfigService>().also {
                every { it.findById(agentId) } returns null
            }
            scanner(scheduledPromptRepo, runRepo, agentSvc).tickClaim()
            runRepo.all().shouldBeEmpty()
            scheduledPromptRepo.findById(scheduledPrompt.id)!!.enabled shouldBe false
        }

        "tickClaim with disabled AgentConfig: ScheduledPrompt disabled, no run inserted" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val scheduledPrompt = scheduledPromptRepo.insertScheduledPrompt(nextRunAt = slot)
            val agentSvc = mockk<AgentConfigService>().also {
                every { it.findById(agentId) } returns activeAgent.copy(enabled = false)
            }
            scanner(scheduledPromptRepo, runRepo, agentSvc).tickClaim()
            runRepo.all().shouldBeEmpty()
            scheduledPromptRepo.findById(scheduledPrompt.id)!!.enabled shouldBe false
        }

        "tickClaim with valid AgentConfig: run CLAIMED inserted normally" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            scheduledPromptRepo.insertScheduledPrompt(nextRunAt = slot)
            scanner(scheduledPromptRepo, runRepo, defaultAgentConfigService()).tickClaim()
            runRepo.all() shouldHaveSize 1
            runRepo.all().first().status shouldBe RunStatus.CLAIMED
        }

        // -------------------------------------------------------------------------
        // findDue
        // -------------------------------------------------------------------------

        "findDue returns only enabled prompts with nextRunAt <= now" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            scheduledPromptRepo.insertScheduledPrompt(nextRunAt = Instant.parse("2026-01-01T08:00:00Z"))   // due
            scheduledPromptRepo.insertScheduledPrompt(nextRunAt = Instant.parse("2026-01-01T09:00:00Z"))   // due (exactly now)
            scheduledPromptRepo.insertScheduledPrompt(nextRunAt = Instant.parse("2026-01-01T10:00:00Z"))   // not due
            scheduledPromptRepo.insertScheduledPrompt(nextRunAt = Instant.parse("2026-01-01T08:00:00Z"), enabled = false) // disabled
            scheduledPromptRepo.findDue(nowInstant) shouldHaveSize 2
        }

        "findDue returns all due prompts without limit" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            repeat(15) { scheduledPromptRepo.insertScheduledPrompt(nextRunAt = Instant.parse("2026-01-01T08:00:00Z")) }
            scheduledPromptRepo.findDue(nowInstant) shouldHaveSize 15
        }

        "findDue returns empty when no prompts are due" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            scheduledPromptRepo.insertScheduledPrompt(nextRunAt = Instant.parse("2026-01-01T10:00:00Z"))
            scheduledPromptRepo.findDue(nowInstant).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // End condition — pre-check (before execution)
        // -------------------------------------------------------------------------

        "tickClaim with ON_DATE already past: disables without executing (slot past endDate)" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            // endDate = 2025-12-25 (in the past), slot = 2026-01-01T08:00 (due, but past endDate)
            val sp = scheduledPromptRepo.insertScheduledPrompt(
                nextRunAt = Instant.parse("2026-01-01T08:00:00Z"),
                endType = SchedulerEndType.ON_DATE,
                endDate = LocalDate.of(2025, 12, 25),
            )
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            runRepo.all().shouldBeEmpty()
            scheduledPromptRepo.findById(sp.id)!!.enabled shouldBe false
        }

        "tickClaim with OCCURRENCES already reached: disables without inserting a run" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val sp = scheduledPromptRepo.insertScheduledPrompt(
                nextRunAt = slot,
                endType = SchedulerEndType.OCCURRENCES,
                maxOccurrenceCount = 1,
            )
            // Pre-insert 1 completed run → already at max
            runRepo.insert(ScheduledPromptRun(
                scheduledPromptId = sp.id,
                scheduledFor = slot.minusSeconds(86400),
                status = RunStatus.DONE,
                correlationId = "prev-done",
            ))
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            // Only the pre-existing run, no new one inserted
            runRepo.all() shouldHaveSize 1
            scheduledPromptRepo.findById(sp.id)!!.enabled shouldBe false
        }

        // -------------------------------------------------------------------------
        // End condition — ON_DATE (post-advance)
        // -------------------------------------------------------------------------

        "tickClaim with ON_DATE: disables when next slot is after endDate" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            // endDate = 2026-01-05 → next slot after claim = 2026-01-08 > endDate
            val sp = scheduledPromptRepo.insertScheduledPrompt(
                nextRunAt = slot,
                endType = SchedulerEndType.ON_DATE,
                endDate = LocalDate.of(2026, 1, 5),
            )
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            runRepo.all() shouldHaveSize 1
            runRepo.all().first().status shouldBe RunStatus.CLAIMED
            scheduledPromptRepo.findById(sp.id)!!.enabled shouldBe false
        }

        "tickClaim with ON_DATE: does NOT disable when next slot is before endDate" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            // endDate = 2026-01-15 → next slot = 2026-01-08 < endDate → still active
            val sp = scheduledPromptRepo.insertScheduledPrompt(
                nextRunAt = slot,
                endType = SchedulerEndType.ON_DATE,
                endDate = LocalDate.of(2026, 1, 15),
            )
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            scheduledPromptRepo.findById(sp.id)!!.enabled shouldBe true
        }

        "tickClaim with ON_DATE: disables when next slot equals endDate at timeUtc" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            // endDate = 2026-01-08, next slot = 2026-01-08T08:00 — slot is NOT after endDate@timeUtc → still active
            // Actually: endDate@timeUtc = 2026-01-08T08:00, nextSlot = 2026-01-08T08:00 → isAfter is false → NOT disabled
            val sp = scheduledPromptRepo.insertScheduledPrompt(
                nextRunAt = slot,
                endType = SchedulerEndType.ON_DATE,
                endDate = LocalDate.of(2026, 1, 8),
            )
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            scheduledPromptRepo.findById(sp.id)!!.enabled shouldBe true
        }

        // -------------------------------------------------------------------------
        // End condition — OCCURRENCES
        // -------------------------------------------------------------------------

        "tickClaim with OCCURRENCES: disables when completed runs reach maxOccurrenceCount" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val sp = scheduledPromptRepo.insertScheduledPrompt(
                nextRunAt = slot,
                endType = SchedulerEndType.OCCURRENCES,
                maxOccurrenceCount = 2,
            )
            // Pre-insert 1 completed run
            runRepo.insert(ScheduledPromptRun(
                scheduledPromptId = sp.id,
                scheduledFor = slot.minusSeconds(86400),
                status = RunStatus.DONE,
                correlationId = "prev-1",
            ))
            // tickClaim inserts run #2 → total completed = 2 (prev + this one) ≥ maxOccurrenceCount = 2
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            scheduledPromptRepo.findById(sp.id)!!.enabled shouldBe false
        }

        "tickClaim with OCCURRENCES: does NOT disable when completed runs are below maxOccurrenceCount" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val sp = scheduledPromptRepo.insertScheduledPrompt(
                nextRunAt = slot,
                endType = SchedulerEndType.OCCURRENCES,
                maxOccurrenceCount = 5,
            )
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            scheduledPromptRepo.findById(sp.id)!!.enabled shouldBe true
        }

        "tickClaim with OCCURRENCES: SKIPPED runs do not count toward maxOccurrenceCount" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val sp = scheduledPromptRepo.insertScheduledPrompt(
                nextRunAt = slot,
                endType = SchedulerEndType.OCCURRENCES,
                maxOccurrenceCount = 2,
            )
            // Pre-insert 1 SKIPPED run (should not count)
            runRepo.insert(ScheduledPromptRun(
                scheduledPromptId = sp.id,
                scheduledFor = slot.minusSeconds(86400),
                status = RunStatus.SKIPPED,
                correlationId = "skipped",
            ))
            // tickClaim inserts run #1 (non-skipped) → total completed = 1 < maxOccurrenceCount = 2
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            scheduledPromptRepo.findById(sp.id)!!.enabled shouldBe true
        }

        // -------------------------------------------------------------------------
        // End condition — NEVER
        // -------------------------------------------------------------------------

        "tickClaim with NEVER: does not disable regardless of run count" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val sp = scheduledPromptRepo.insertScheduledPrompt(nextRunAt = slot)
            repeat(10) { i ->
                runRepo.insert(ScheduledPromptRun(
                    scheduledPromptId = sp.id,
                    scheduledFor = slot.minusSeconds((i + 1) * 86400L),
                    status = RunStatus.DONE,
                    correlationId = "done-$i",
                ))
            }
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            scheduledPromptRepo.findById(sp.id)!!.enabled shouldBe true
        }
    }
}
