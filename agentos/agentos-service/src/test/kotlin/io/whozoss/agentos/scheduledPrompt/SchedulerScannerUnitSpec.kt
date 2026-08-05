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
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.prompt.PromptService
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
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
 * A real [ScheduledPromptExecutor] is used (not a mock) so that the RUNNING/DONE transition
 * happens inside [ScheduledPromptExecutor.materialize], exactly as it does in production.
 * The [scanner] factory uses an empty user provider (0 UserRuns created), so materialize
 * transitions Runs directly to DONE. Tests that need RUNNING Runs use [scannerWithUserRunRepo]
 * with a non-empty target user set.
 * Services that are not exercised by Phase A
 * (Phase B: [PromptService], [CaseService], [PermissionService], [UserService]) are
 * relaxed mocks.
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
        val userRunRepo = InMemoryScheduledPromptUserRunRepository()
        runRepo.userRunRepository = userRunRepo
        val executor = ScheduledPromptExecutor(
            scheduledPromptRepository = scheduledPromptRepo,
            runRepository = runRepo,
            userRunRepository = userRunRepo,
            promptService = mockk(relaxed = true),
            agentConfigService = mockk(relaxed = true),
            caseService = mockk(relaxed = true),
            permissionService = mockk(relaxed = true),
            userService = mockk(relaxed = true),
            properties = properties,
            clock = clock,
        )
        return SchedulerScanner(
            scheduledPromptRepository = scheduledPromptRepo,
            runRepository = runRepo,
            userRunRepository = userRunRepo,
            agentConfigService = agentConfigService,
            properties = properties,
            clock = clock,
            nextRunCalculatorService = NextRunCalculatorService(clock = clock),
            executor = executor,
        )
    }

    /**
     * Builds a [SchedulerScanner] backed by an [InMemoryScheduledPromptUserRunRepository]
     * that resolves target users via [targetUserIdsProvider]. Returns both so tests can
     * seed UserRuns directly.
     */
    private fun scannerWithUserRunRepo(
        scheduledPromptRepo: InMemoryScheduledPromptRepository,
        runRepo: InMemoryScheduledPromptRunRepository,
        targetUserIdsProvider: (agentConfigId: UUID, namespaceId: UUID) -> Set<UUID>,
        agentConfigService: AgentConfigService = defaultAgentConfigService(),
    ): Pair<SchedulerScanner, InMemoryScheduledPromptUserRunRepository> {
        val userRunRepo = InMemoryScheduledPromptUserRunRepository(targetUserIdsProvider)
        runRepo.userRunRepository = userRunRepo
        val executor = ScheduledPromptExecutor(
            scheduledPromptRepository = scheduledPromptRepo,
            runRepository = runRepo,
            userRunRepository = userRunRepo,
            promptService = mockk(relaxed = true),
            agentConfigService = mockk(relaxed = true),
            caseService = mockk(relaxed = true),
            permissionService = mockk(relaxed = true),
            userService = mockk(relaxed = true),
            properties = properties,
            clock = clock,
        )
        val scanner = SchedulerScanner(
            scheduledPromptRepository = scheduledPromptRepo,
            runRepository = runRepo,
            userRunRepository = userRunRepo,
            agentConfigService = agentConfigService,
            properties = properties,
            clock = clock,
            nextRunCalculatorService = NextRunCalculatorService(clock = clock),
            executor = executor,
        )
        return scanner to userRunRepo
    }

    private val defaultNamespaceId: UUID = UUID.randomUUID()

    private fun InMemoryScheduledPromptRepository.insertScheduledPrompt(
        nextRunAt: Instant,
        enabled: Boolean = true,
        startDate: LocalDate = today,
        timeUtc: LocalTime = defaultTime,
        endType: SchedulerEndType = SchedulerEndType.NEVER,
        endDate: LocalDate? = null,
        maxOccurrenceCount: Int? = null,
        namespaceId: UUID? = defaultNamespaceId,
    ): ScheduledPrompt {
        val scheduledPrompt = ScheduledPrompt(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = namespaceId,
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

        "tickClaim with 1 due prompt: run inserted and transitioned to DONE (no target users)" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            scheduledPromptRepo.insertScheduledPrompt(nextRunAt = slot)
            // scanner() uses an empty user provider — 0 UserRuns created — materialize transitions to DONE
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            val runs = runRepo.all()
            runs shouldHaveSize 1
            runs.first().status shouldBe RunStatus.DONE
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
            val targetUserId = UUID.randomUUID()
            // Use scannerWithUserRunRepo so the RUNNING run has an active PENDING UserRun —
            // the settled-running sweep must NOT close it, preserving the overlap guard.
            val (scanner, userRunRepo) = scannerWithUserRunRepo(
                scheduledPromptRepo, runRepo,
                targetUserIdsProvider = { _, _ -> setOf(targetUserId) },
            )
            val existingRun = runRepo.insert(
                ScheduledPromptRun(
                    scheduledPromptId = scheduledPrompt.id,
                    scheduledFor = slot.minusSeconds(3600),
                    status = RunStatus.RUNNING,
                    correlationId = "running",
                ),
            )
            // Seed a PENDING UserRun so the sweep sees a non-terminal UserRun and skips it.
            userRunRepo.materialize(existingRun.id, scheduledPrompt.agentConfigId, scheduledPrompt.namespaceId!!)

            scanner.tickClaim()

            // Existing run untouched — still has a PENDING UserRun, not settled.
            runRepo.findById(existingRun.id)!!.status shouldBe RunStatus.RUNNING
            // New run for the due slot is SKIPPED because hasActive() returned true.
            runRepo.all().filter { it.correlationId != "running" }.first().status shouldBe RunStatus.SKIPPED
        }

        "tickClaim with DONE run: new run inserted and transitioned to DONE (DONE is not active)" {
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
            // DONE is not active — hasActive() returns false — new run is CLAIMED then materialised to DONE
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            runRepo.all().filter { it.correlationId != "done-run" }.first().status shouldBe RunStatus.DONE
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

        "tickClaim with valid AgentConfig: run inserted and transitioned to DONE (no target users)" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            scheduledPromptRepo.insertScheduledPrompt(nextRunAt = slot)
            scanner(scheduledPromptRepo, runRepo, defaultAgentConfigService()).tickClaim()
            runRepo.all() shouldHaveSize 1
            runRepo.all().first().status shouldBe RunStatus.DONE
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
            runRepo.all().first().status shouldBe RunStatus.DONE
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

        "tickClaim with ON_DATE: last valid run is the day before endDate (exclusive at midnight)" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            // endDate = 2026-01-08 (exclusive) → endInstant = 2026-01-08T00:00Z
            // current slot Jan 1 08:00 < Jan 8 00:00 → runs
            // next slot Jan 8 08:00 >= Jan 8 00:00 → !isBefore → disables after
            val sp = scheduledPromptRepo.insertScheduledPrompt(
                nextRunAt = slot,
                endType = SchedulerEndType.ON_DATE,
                endDate = LocalDate.of(2026, 1, 8),
            )
            scanner(scheduledPromptRepo, runRepo).tickClaim()
            runRepo.all() shouldHaveSize 1
            runRepo.all().first().status shouldBe RunStatus.DONE
            scheduledPromptRepo.findById(sp.id)!!.enabled shouldBe false
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
        // Orphaned CLAIMED sweep (crash recovery)
        // -------------------------------------------------------------------------

        "tickClaim sweeps orphaned CLAIMED runs older than threshold — marks FAILED" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val sp = scheduledPromptRepo.insertScheduledPrompt(nextRunAt = Instant.parse("2026-01-01T10:00:00Z")) // not due
            // Insert a CLAIMED run with creation time well in the past (orphaned)
            val orphanedRun = ScheduledPromptRun(
                metadata = EntityMetadata(id = UUID.randomUUID(), created = Instant.parse("2026-01-01T08:00:00Z")),
                scheduledPromptId = sp.id,
                scheduledFor = Instant.parse("2026-01-01T08:00:00Z"),
                status = RunStatus.CLAIMED,
                correlationId = "orphaned",
            )
            runRepo.insert(orphanedRun)

            scanner(scheduledPromptRepo, runRepo).tickClaim()

            val updated = runRepo.findById(orphanedRun.id)!!
            updated.status shouldBe RunStatus.FAILED
            updated.error shouldBe "Orphaned CLAIMED — materialize never completed (crash recovery)"
        }

        "tickClaim does NOT sweep recent CLAIMED runs (within threshold)" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val sp = scheduledPromptRepo.insertScheduledPrompt(nextRunAt = Instant.parse("2026-01-01T10:00:00Z")) // not due
            // Insert a CLAIMED run with creation time = now (recent, not orphaned)
            val recentRun = ScheduledPromptRun(
                metadata = EntityMetadata(id = UUID.randomUUID(), created = nowInstant),
                scheduledPromptId = sp.id,
                scheduledFor = nowInstant,
                status = RunStatus.CLAIMED,
                correlationId = "recent",
            )
            runRepo.insert(recentRun)

            scanner(scheduledPromptRepo, runRepo).tickClaim()

            val updated = runRepo.findById(recentRun.id)!!
            updated.status shouldBe RunStatus.CLAIMED // not touched
        }

        "tickClaim sweep unblocks hasActive guard — next slot is CLAIMED not SKIPPED" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val slot = Instant.parse("2026-01-01T08:00:00Z")
            val sp = scheduledPromptRepo.insertScheduledPrompt(nextRunAt = slot)
            // Insert an orphaned CLAIMED run for a previous slot
            val orphanedRun = ScheduledPromptRun(
                metadata = EntityMetadata(id = UUID.randomUUID(), created = Instant.parse("2025-12-31T08:00:00Z")),
                scheduledPromptId = sp.id,
                scheduledFor = Instant.parse("2025-12-31T08:00:00Z"),
                status = RunStatus.CLAIMED,
                correlationId = "orphaned",
            )
            runRepo.insert(orphanedRun)

            // Without the sweep, hasActive() would return true → SKIPPED
            // With the sweep, the orphan is marked FAILED first → hasActive() returns false → CLAIMED
            scanner(scheduledPromptRepo, runRepo).tickClaim()

            val runs = runRepo.all()
            val orphan = runs.first { it.correlationId == "orphaned" }
            orphan.status shouldBe RunStatus.FAILED

            val newRun = runs.first { it.correlationId != "orphaned" }
            newRun.status shouldBe RunStatus.DONE
        }

        // -------------------------------------------------------------------------
        // Orphaned RUNNING sweep (crash recovery)
        // -------------------------------------------------------------------------

        "tickClaim closes orphaned RUNNING run as DONE when all UserRuns are settled" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            // ScheduledPrompt not due — the RUNNING sweep must fire independently of findDue
            val sp = scheduledPromptRepo.insertScheduledPrompt(nextRunAt = Instant.parse("2026-01-01T10:00:00Z"))
            val targetUserId = UUID.randomUUID()
            val (scanner, userRunRepo) = scannerWithUserRunRepo(
                scheduledPromptRepo, runRepo,
                targetUserIdsProvider = { _, _ -> setOf(targetUserId) },
            )

            // Insert a RUNNING run directly (simulates a run that was materialised but whose
            // checkCompletion call was lost in a crash)
            val run = ScheduledPromptRun(
                metadata = EntityMetadata(id = UUID.randomUUID(), created = Instant.parse("2026-01-01T08:00:00Z")),
                scheduledPromptId = sp.id,
                scheduledFor = Instant.parse("2026-01-01T08:00:00Z"),
                status = RunStatus.RUNNING,
                correlationId = "orphaned-running",
            )
            runRepo.insert(run)

            // Seed a terminal UserRun (DONE) — materialize then mark terminal
            userRunRepo.materialize(run.id, sp.agentConfigId, sp.namespaceId!!)
            val ur = userRunRepo.findByRunId(run.id).first()
            userRunRepo.markTerminal(ur.id, UserRunStatus.DONE, nowInstant)

            scanner.tickClaim()

            runRepo.findById(run.id)!!.status shouldBe RunStatus.DONE
        }

        "tickClaim closes orphaned RUNNING run as FAILED when a UserRun has failed" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val sp = scheduledPromptRepo.insertScheduledPrompt(nextRunAt = Instant.parse("2026-01-01T10:00:00Z"))
            val targetUserId = UUID.randomUUID()
            val (scanner, userRunRepo) = scannerWithUserRunRepo(
                scheduledPromptRepo, runRepo,
                targetUserIdsProvider = { _, _ -> setOf(targetUserId) },
            )

            val run = ScheduledPromptRun(
                metadata = EntityMetadata(id = UUID.randomUUID(), created = Instant.parse("2026-01-01T08:00:00Z")),
                scheduledPromptId = sp.id,
                scheduledFor = Instant.parse("2026-01-01T08:00:00Z"),
                status = RunStatus.RUNNING,
                correlationId = "orphaned-running-failed",
            )
            runRepo.insert(run)

            userRunRepo.materialize(run.id, sp.agentConfigId, sp.namespaceId!!)
            val ur = userRunRepo.findByRunId(run.id).first()
            userRunRepo.markTerminal(ur.id, UserRunStatus.FAILED, nowInstant, "boom")

            scanner.tickClaim()

            runRepo.findById(run.id)!!.status shouldBe RunStatus.FAILED
        }

        "tickClaim does NOT close RUNNING run when UserRuns are still active" {
            val scheduledPromptRepo = makeScheduledPromptRepo()
            val runRepo = makeRunRepo()
            val sp = scheduledPromptRepo.insertScheduledPrompt(nextRunAt = Instant.parse("2026-01-01T10:00:00Z"))
            val targetUserId = UUID.randomUUID()
            val (scanner, userRunRepo) = scannerWithUserRunRepo(
                scheduledPromptRepo, runRepo,
                targetUserIdsProvider = { _, _ -> setOf(targetUserId) },
            )

            val run = ScheduledPromptRun(
                metadata = EntityMetadata(id = UUID.randomUUID(), created = Instant.parse("2026-01-01T08:00:00Z")),
                scheduledPromptId = sp.id,
                scheduledFor = Instant.parse("2026-01-01T08:00:00Z"),
                status = RunStatus.RUNNING,
                correlationId = "still-running",
            )
            runRepo.insert(run)

            // Materialize but do NOT mark terminal — UserRun stays PENDING
            userRunRepo.materialize(run.id, sp.agentConfigId, sp.namespaceId!!)

            scanner.tickClaim()

            runRepo.findById(run.id)!!.status shouldBe RunStatus.RUNNING
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
