package io.whozoss.agentos.scheduledPrompt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRuntime
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.prompt.Prompt
import io.whozoss.agentos.prompt.PromptService
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * End-to-end scenario tests for the scheduled prompt batch pipeline.
 *
 * Each test drives the full sequence tickClaim() → tickConsume() and verifies the
 * cumulative state transitions across both phases, including crash-recovery sweeps.
 *
 * Uses in-memory repositories and MockK stubs — no Spring context, no Neo4j.
 * Fixed clock: 2026-01-01 09:00:00 UTC (Thursday).
 *
 * Complements [SchedulerScannerUnitSpec] and [ScheduledPromptExecutorUnitSpec] which
 * test each phase in isolation. These scenarios validate the cross-phase orchestration.
 */
class ScheduledPromptBatchScenarioSpec : StringSpec() {

    private val nowInstant = Instant.parse("2026-01-01T09:00:00Z")
    private val clock = Clock.fixed(nowInstant, ZoneOffset.UTC)

    private val namespaceId: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val agentId: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
    private val promptTemplateId: UUID = UUID.fromString("30000000-0000-0000-0000-000000000001")
    private val userId1: UUID = UUID.fromString("40000000-0000-0000-0000-000000000001")
    private val userId2: UUID = UUID.fromString("40000000-0000-0000-0000-000000000002")

    private val properties = SchedulerProperties(batchSize = 10, leaseMinutes = 30L)

    private val activeAgent = AgentConfig(
        metadata = EntityMetadata(id = agentId, version = 0L),
        namespaceId = namespaceId,
        name = "weekly-agent",
        enabled = true,
    )
    private val promptTemplate = Prompt(
        metadata = EntityMetadata(id = promptTemplateId),
        name = "digest-template",
        content = listOf("Run your weekly digest."),
    )
    private val user1 = User(
        metadata = EntityMetadata(id = userId1),
        externalId = "user1@example.com",
        firstname = "Alice",
        lastname = "Smith",
    )
    private val user2 = User(
        metadata = EntityMetadata(id = userId2),
        externalId = "user2@example.com",
        firstname = "Bob",
        lastname = "Jones",
    )

    // ---------------------------------------------------------------------------
    // Fixture builders
    // ---------------------------------------------------------------------------

    private fun makeScheduledPrompt(
        spRepo: InMemoryScheduledPromptRepository,
        slot: Instant = Instant.parse("2026-01-01T08:00:00Z"),
    ): ScheduledPrompt = spRepo.save(
        ScheduledPrompt(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = namespaceId,
            agentConfigId = agentId,
            promptTemplateId = promptTemplateId,
            name = "weekly-digest",
            recurrence = Recurrence(unit = SchedulerUnit.WEEK, timeUtc = LocalTime.of(8, 0)),
            planning = Planning(startDate = LocalDate.of(2026, 1, 1), endType = SchedulerEndType.NEVER),
            enabled = true,
            nextRunAt = slot,
        ),
    )

    private fun makeScanner(
        spRepo: InMemoryScheduledPromptRepository,
        runRepo: InMemoryScheduledPromptRunRepository,
        userRunRepo: InMemoryScheduledPromptUserRunRepository,
        caseService: CaseService,
        agentConfigService: AgentConfigService = mockk<AgentConfigService>().also {
            every { it.findById(agentId) } returns activeAgent
        },
        userService: UserService = mockk<UserService>().also {
            every { it.findById(userId1) } returns user1
            every { it.findById(userId2) } returns user2
        },
    ): SchedulerScanner {
        runRepo.userRunRepository = userRunRepo
        val promptService = mockk<PromptService>().also {
            every { it.findById(promptTemplateId) } returns promptTemplate
        }
        val executor = ScheduledPromptExecutor(
            scheduledPromptRepository = spRepo,
            runRepository = runRepo,
            userRunRepository = userRunRepo,
            promptService = promptService,
            agentConfigService = agentConfigService,
            caseService = caseService,
            permissionService = mockk<PermissionService>(relaxed = true),
            userService = userService,
            properties = properties,
            clock = clock,
        )
        return SchedulerScanner(
            scheduledPromptRepository = spRepo,
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
     * CaseService where each Case's runtime transitions to IDLE after addMessage is called,
     * simulating a fast agent turn completing synchronously from the test's perspective.
     *
     * The runtime map is keyed by caseId and populated in `create` so that `findActiveRuntime`
     * and `addMessage` can look up the correct flow. All state is local to the returned mock.
     */
    private fun eventuallyIdleCaseService(): CaseService {
        val runtimeMap = mutableMapOf<UUID, CaseRuntime>()
        return mockk<CaseService>(relaxed = true).also { svc ->
            every { svc.create(any()) } answers {
                val id = UUID.randomUUID()
                // statusFlow starts at IDLE rather than transitioning via addMessage callback.
                // Reason: addMessage's sessionContext parameter is Map<String, Any?>? (nullable).
                // MockK 1.13.x any<T>() requires T : Any, so there is no matcher for nullable
                // types usable in every {}. Starting at IDLE avoids needing to mock addMessage
                // at all — monitorLaunch sees the terminal state immediately, which is
                // equivalent for what these scenario tests verify (end-to-end UserRunStatus).
                val flow = MutableStateFlow(CaseStatus.IDLE)
                val rt = mockk<CaseRuntime>(relaxed = true).also { every { it.statusFlow } returns flow }
                runtimeMap[id] = rt
                Case(metadata = EntityMetadata(id = id), namespaceId = namespaceId)
            }
            every { svc.findActiveRuntime(any()) } answers { runtimeMap[firstArg<UUID>()] }
        }
    }

    // ---------------------------------------------------------------------------
    // Scenarios
    // ---------------------------------------------------------------------------

    init {

        "happy path: tickClaim then tickConsume closes Run as DONE" {
            val spRepo = InMemoryScheduledPromptRepository()
            val runRepo = InMemoryScheduledPromptRunRepository()
            val userRunRepo = InMemoryScheduledPromptUserRunRepository { _, _ -> setOf(userId1, userId2) }
            makeScheduledPrompt(spRepo)

            val scanner = makeScanner(
                spRepo, runRepo, userRunRepo,
                caseService = eventuallyIdleCaseService(),
            )

            // Phase A: claim the due slot, materialise UserRuns
            scanner.tickClaim()

            val runAfterClaim = runRepo.all().single()
            runAfterClaim.status shouldBe RunStatus.RUNNING
            userRunRepo.all() shouldHaveSize 2
            userRunRepo.all().all { it.status == UserRunStatus.PENDING } shouldBe true

            // Phase B: claim and execute UserRuns; Cases reach IDLE → UserRuns DONE → Run DONE
            scanner.tickConsume()

            val runAfterConsume = runRepo.findById(runAfterClaim.id)!!
            runAfterConsume.status shouldBe RunStatus.DONE
            userRunRepo.all().all { it.status == UserRunStatus.DONE } shouldBe true
        }

        "crash Phase A: orphaned CLAIMED Run is swept to FAILED, next tick creates new Run" {
            val spRepo = InMemoryScheduledPromptRepository()
            val runRepo = InMemoryScheduledPromptRunRepository()
            val userRunRepo = InMemoryScheduledPromptUserRunRepository { _, _ -> setOf(userId1) }
            val sp = makeScheduledPrompt(spRepo, slot = Instant.parse("2026-01-01T08:00:00Z"))

            // Simulate crash: insert a CLAIMED Run older than the orphan threshold (5 min)
            // as if materialize never completed.
            val orphanedRun = ScheduledPromptRun(
                metadata = EntityMetadata(
                    id = UUID.randomUUID(),
                    created = Instant.parse("2025-12-31T08:00:00Z"),
                ),
                scheduledPromptId = sp.id,
                scheduledFor = Instant.parse("2025-12-31T08:00:00Z"),
                status = RunStatus.CLAIMED,
                correlationId = "orphaned-claimed",
            )
            runRepo.insert(orphanedRun)

            val scanner = makeScanner(
                spRepo, runRepo, userRunRepo,
                caseService = eventuallyIdleCaseService(),
            )

            // tickClaim: sweep marks orphan FAILED (unblocking hasActive guard),
            // then claims the due slot → materialises → RUNNING.
            scanner.tickClaim()

            runRepo.findById(orphanedRun.id)!!.status shouldBe RunStatus.FAILED
            val newRun = runRepo.all().first { it.id != orphanedRun.id }
            newRun.status shouldBe RunStatus.RUNNING
            userRunRepo.findByRunId(newRun.id) shouldHaveSize 1
        }

        "crash Phase B: orphaned RUNNING Run is swept to DONE on next tickClaim" {
            val spRepo = InMemoryScheduledPromptRepository()
            val runRepo = InMemoryScheduledPromptRunRepository()
            val userRunRepo = InMemoryScheduledPromptUserRunRepository { _, _ -> setOf(userId1) }
            val sp = makeScheduledPrompt(spRepo, slot = Instant.parse("2026-01-01T10:00:00Z")) // not due

            // Simulate crash: Run is RUNNING but checkCompletion was never called.
            val orphanedRun = runRepo.insert(
                ScheduledPromptRun(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    scheduledPromptId = sp.id,
                    scheduledFor = Instant.parse("2026-01-01T08:00:00Z"),
                    status = RunStatus.RUNNING,
                    correlationId = "orphaned-running",
                ),
            )
            // Seed a terminal UserRun — all settled, but the Run was never closed.
            userRunRepo.materialize(orphanedRun.id, agentId, namespaceId)
            userRunRepo.markTerminal(
                userRunRepo.findByRunId(orphanedRun.id).first().id,
                UserRunStatus.DONE,
                nowInstant,
            )

            val scanner = makeScanner(
                spRepo, runRepo, userRunRepo,
                caseService = mockk(relaxed = true), // no due prompt — consume not invoked
            )

            // tickClaim sweeps the orphaned RUNNING run → DONE (no due prompts to claim)
            scanner.tickClaim()

            runRepo.findById(orphanedRun.id)!!.status shouldBe RunStatus.DONE
        }

        "multi-batch: all UserRuns processed when count exceeds batchSize" {
            val userIds = (1..5).map { UUID.randomUUID() }.toSet()
            val spRepo = InMemoryScheduledPromptRepository()
            val runRepo = InMemoryScheduledPromptRunRepository()
            val userRunRepo = InMemoryScheduledPromptUserRunRepository { _, _ -> userIds }
            makeScheduledPrompt(spRepo)

            // batchSize = 2 → 3 batches needed for 5 UserRuns
            val smallBatchProperties = SchedulerProperties(batchSize = 2, leaseMinutes = 30L)
            val userService = mockk<UserService>().also { svc ->
                userIds.forEach { id ->
                    every { svc.findById(id) } returns User(
                        metadata = EntityMetadata(id = id),
                        externalId = "$id@example.com",
                        firstname = "User",
                        lastname = id.toString().take(6),
                    )
                }
            }

            val caseService = eventuallyIdleCaseService()

            runRepo.userRunRepository = userRunRepo
            val promptService = mockk<PromptService>().also {
                every { it.findById(promptTemplateId) } returns promptTemplate
            }
            val agentConfigService = mockk<AgentConfigService>().also {
                every { it.findById(agentId) } returns activeAgent
            }
            val executor = ScheduledPromptExecutor(
                scheduledPromptRepository = spRepo,
                runRepository = runRepo,
                userRunRepository = userRunRepo,
                promptService = promptService,
                agentConfigService = agentConfigService,
                caseService = caseService,
                permissionService = mockk(relaxed = true),
                userService = userService,
                properties = smallBatchProperties,
                clock = clock,
            )
            val scanner = SchedulerScanner(
                scheduledPromptRepository = spRepo,
                runRepository = runRepo,
                userRunRepository = userRunRepo,
                agentConfigService = agentConfigService,
                properties = smallBatchProperties,
                clock = clock,
                nextRunCalculatorService = NextRunCalculatorService(clock = clock),
                executor = executor,
            )

            // Phase A
            scanner.tickClaim()
            userRunRepo.all() shouldHaveSize 5

            // Phase B: 3 batches of 2, 2, 1 — all 5 UserRuns must be processed
            scanner.tickConsume()

            userRunRepo.all().all { it.status == UserRunStatus.DONE } shouldBe true
            runRepo.findById(runRepo.all().single().id)!!.status shouldBe RunStatus.DONE
        }

        "crash Phase B: orphaned RUNNING Run with failed UserRun is swept to FAILED" {
            val spRepo = InMemoryScheduledPromptRepository()
            val runRepo = InMemoryScheduledPromptRunRepository()
            val userRunRepo = InMemoryScheduledPromptUserRunRepository { _, _ -> setOf(userId1) }
            val sp = makeScheduledPrompt(spRepo, slot = Instant.parse("2026-01-01T10:00:00Z")) // not due

            val orphanedRun = runRepo.insert(
                ScheduledPromptRun(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    scheduledPromptId = sp.id,
                    scheduledFor = Instant.parse("2026-01-01T08:00:00Z"),
                    status = RunStatus.RUNNING,
                    correlationId = "orphaned-running-failed",
                ),
            )
            userRunRepo.materialize(orphanedRun.id, agentId, namespaceId)
            userRunRepo.markTerminal(
                userRunRepo.findByRunId(orphanedRun.id).first().id,
                UserRunStatus.FAILED,
                nowInstant,
                "Case creation failed",
            )

            val scanner = makeScanner(
                spRepo, runRepo, userRunRepo,
                caseService = mockk(relaxed = true),
            )

            scanner.tickClaim()

            runRepo.findById(orphanedRun.id)!!.status shouldBe RunStatus.FAILED
        }
    }
}
