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

    private fun makeSpRepo() = InMemoryScheduledPromptRepository()
    private fun makeRunRepo() = InMemoryScheduledPromptRunRepository()

    private fun scanner(
        spRepo: InMemoryScheduledPromptRepository,
        runRepo: InMemoryScheduledPromptRunRepository,
        agentConfigService: AgentConfigService = defaultAgentConfigService(),
    ) = SchedulerScanner(spRepo, runRepo, agentConfigService, properties, clock)

    private fun InMemoryScheduledPromptRepository.insertSp(
        nextRunAt: Instant,
        enabled: Boolean = true,
        startDate: LocalDate = today,
        timeUtc: LocalTime = defaultTime,
    ): ScheduledPrompt {
        val sp = ScheduledPrompt(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            agentConfigId = agentId,
            promptTemplateId = promptId,
            name = "sp-${UUID.randomUUID().toString().take(6)}",
            recurrence = Recurrence(unit = SchedulerUnit.WEEK, timeUtc = timeUtc),
            planning = Planning(startDate = startDate, endType = SchedulerEndType.NEVER),
            enabled = enabled,
            nextRunAt = nextRunAt,
        )
        return save(sp)
    }

    init {
        // -------------------------------------------------------------------------
        // Tick — no due prompts
        // -------------------------------------------------------------------------

        "tick with no due prompts: no runs created" {
            val spRepo = makeSpRepo()
            val runRepo = makeRunRepo()
            spRepo.insertSp(nextRunAt = Instant.parse("2026-01-01T10:00:00Z"))
            scanner(spRepo, runRepo).tick()
            runRepo.all().shouldBeEmpty()
        }

        "tick with disabled prompt: no runs created" {
            val spRepo = makeSpRepo()
            val runRepo = makeRunRepo()
            spRepo.insertSp(nextRunAt = Instant.parse("2026-01-01T08:00:00Z"), enabled = false)
            scanner(spRepo, runRepo).tick()
            runRepo.all().shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Tick — one due prompt, happy path
        // -------------------------------------------------------------------------

        "tick with 1 due prompt: run CLAIMED inserted" {
            val spRepo = makeSpRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            spRepo.insertSp(nextRunAt = slot)
            scanner(spRepo, runRepo).tick()
            val runs = runRepo.all()
            runs shouldHaveSize 1
            runs.first().status shouldBe RunStatus.CLAIMED
        }

        "tick with 1 due prompt: nextRunAt advanced" {
            val spRepo = makeSpRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T09:00:00Z")
            // startDate=2026-01-01 (Thursday), WEEK no filter → next slot = next Thursday 2026-01-08
            val sp = spRepo.insertSp(nextRunAt = slot, timeUtc = LocalTime.of(9, 0))
            scanner(spRepo, runRepo).tick()
            val updated = spRepo.findById(sp.id)!!
            updated.nextRunAt shouldBe Instant.parse("2026-01-08T09:00:00Z")
        }

        "tick with 1 due prompt: run scheduled for the claimed slot" {
            val spRepo = makeSpRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val sp = spRepo.insertSp(nextRunAt = slot)
            scanner(spRepo, runRepo).tick()
            val run = runRepo.all().first()
            run.scheduledPromptId shouldBe sp.id
            run.scheduledFor shouldBe slot
        }

        // -------------------------------------------------------------------------
        // Tick — overlap (active run already exists)
        // -------------------------------------------------------------------------

        "tick with active run already exists: run SKIPPED (overlap)" {
            val spRepo = makeSpRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val sp = spRepo.insertSp(nextRunAt = slot)
            runRepo.insert(
                ScheduledPromptRun(
                    scheduledPromptId = sp.id,
                    scheduledFor = slot.minusSeconds(3600),
                    status = RunStatus.CLAIMED,
                    correlationId = "pre-existing",
                ),
            )
            scanner(spRepo, runRepo).tick()
            val runs = runRepo.all()
            runs shouldHaveSize 2
            runs.filter { it.correlationId != "pre-existing" }.first().status shouldBe RunStatus.SKIPPED
        }

        "tick with RUNNING run already exists: run SKIPPED (overlap)" {
            val spRepo = makeSpRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val sp = spRepo.insertSp(nextRunAt = slot)
            runRepo.insert(
                ScheduledPromptRun(
                    scheduledPromptId = sp.id,
                    scheduledFor = slot.minusSeconds(3600),
                    status = RunStatus.RUNNING,
                    correlationId = "running",
                ),
            )
            scanner(spRepo, runRepo).tick()
            runRepo.all().filter { it.correlationId != "running" }.first().status shouldBe RunStatus.SKIPPED
        }

        "tick with DONE run: run CLAIMED (DONE is not active)" {
            val spRepo = makeSpRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val sp = spRepo.insertSp(nextRunAt = slot)
            runRepo.insert(
                ScheduledPromptRun(
                    scheduledPromptId = sp.id,
                    scheduledFor = slot.minusSeconds(3600),
                    status = RunStatus.DONE,
                    correlationId = "done-run",
                ),
            )
            scanner(spRepo, runRepo).tick()
            runRepo.all().filter { it.correlationId != "done-run" }.first().status shouldBe RunStatus.CLAIMED
        }

        // -------------------------------------------------------------------------
        // Tick — DuplicateRunException (concurrent tick wins the race)
        // -------------------------------------------------------------------------

        "tick with DuplicateRunException: nextRunAt still advanced" {
            val spRepo = makeSpRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            // startDate=2026-01-01 (Thursday), WEEK no filter → next slot = next Thursday 2026-01-08
            val sp = spRepo.insertSp(nextRunAt = slot)
            runRepo.insert(
                ScheduledPromptRun(
                    scheduledPromptId = sp.id,
                    scheduledFor = slot,
                    status = RunStatus.CLAIMED,
                    correlationId = "first-tick",
                ),
            )
            scanner(spRepo, runRepo).tick()
            val updated = spRepo.findById(sp.id)!!
            updated.nextRunAt shouldBe Instant.parse("2026-01-08T08:00:00Z")
        }

        "tick with DuplicateRunException: no extra run inserted" {
            val spRepo = makeSpRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val sp = spRepo.insertSp(nextRunAt = slot)
            runRepo.insert(
                ScheduledPromptRun(
                    scheduledPromptId = sp.id,
                    scheduledFor = slot,
                    status = RunStatus.CLAIMED,
                    correlationId = "first-tick",
                ),
            )
            scanner(spRepo, runRepo).tick()
            runRepo.all() shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // CAS advance
        // -------------------------------------------------------------------------

        "advance CAS: returns true when slot matches" {
            val spRepo = makeSpRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val sp = spRepo.insertSp(nextRunAt = slot)
            spRepo.advance(sp.id, slot, slot.plusSeconds(86400)).shouldBeTrue()
        }

        "advance CAS: returns false when slot does not match" {
            val spRepo = makeSpRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val sp = spRepo.insertSp(nextRunAt = slot)
            spRepo.advance(sp.id, slot.plusSeconds(1), slot.plusSeconds(86400)).shouldBeFalse()
        }

        // -------------------------------------------------------------------------
        // AgentConfig guard
        // -------------------------------------------------------------------------

        "tick with deleted AgentConfig: ScheduledPrompt disabled, no run inserted" {
            val spRepo = makeSpRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val sp = spRepo.insertSp(nextRunAt = slot)
            val agentSvc = mockk<AgentConfigService>().also {
                every { it.findById(agentId) } returns null
            }
            scanner(spRepo, runRepo, agentSvc).tick()
            runRepo.all().shouldBeEmpty()
            spRepo.findById(sp.id)!!.enabled shouldBe false
        }

        "tick with disabled AgentConfig: ScheduledPrompt disabled, no run inserted" {
            val spRepo = makeSpRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val sp = spRepo.insertSp(nextRunAt = slot)
            val agentSvc = mockk<AgentConfigService>().also {
                every { it.findById(agentId) } returns activeAgent.copy(enabled = false)
            }
            scanner(spRepo, runRepo, agentSvc).tick()
            runRepo.all().shouldBeEmpty()
            spRepo.findById(sp.id)!!.enabled shouldBe false
        }

        "tick with valid AgentConfig: run CLAIMED inserted normally" {
            val spRepo = makeSpRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            spRepo.insertSp(nextRunAt = slot)
            scanner(spRepo, runRepo, defaultAgentConfigService()).tick()
            runRepo.all() shouldHaveSize 1
            runRepo.all().first().status shouldBe RunStatus.CLAIMED
        }

        // -------------------------------------------------------------------------
        // findDue
        // -------------------------------------------------------------------------

        "findDue returns only enabled prompts with nextRunAt <= now" {
            val spRepo = makeSpRepo()
            spRepo.insertSp(nextRunAt = Instant.parse("2026-01-01T08:00:00Z"))   // due
            spRepo.insertSp(nextRunAt = Instant.parse("2026-01-01T09:00:00Z"))   // due (exactly now)
            spRepo.insertSp(nextRunAt = Instant.parse("2026-01-01T10:00:00Z"))   // not due
            spRepo.insertSp(nextRunAt = Instant.parse("2026-01-01T08:00:00Z"), enabled = false) // disabled
            spRepo.findDue(nowInstant) shouldHaveSize 2
        }

        "findDue returns all due prompts without limit" {
            val spRepo = makeSpRepo()
            repeat(15) { spRepo.insertSp(nextRunAt = Instant.parse("2026-01-01T08:00:00Z")) }
            spRepo.findDue(nowInstant) shouldHaveSize 15
        }

        "findDue returns empty when no prompts are due" {
            val spRepo = makeSpRepo()
            spRepo.insertSp(nextRunAt = Instant.parse("2026-01-01T10:00:00Z"))
            spRepo.findDue(nowInstant).shouldBeEmpty()
        }
    }
}
