package io.whozoss.agentos.scheduledPrompt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRuntime
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.caseEvent.MessageContent
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
 * Unit tests for [ScheduledPromptExecutor] - converged path only.
 *
 * Phase B (processUserRun, UserRun poller, start/stop, dispatcher) has been deliberately
 * removed from the production code. All tests covering that removed behaviour have been
 * deleted with it.
 *
 * Tests covering synthesizer behaviour (explicit loopConfig / null loopConfig) have been
 * deleted: the synthesizer was moved to [ScheduledPromptLoopConfigResolver] and is no
 * longer a constructor parameter of [ScheduledPromptExecutor].
 *
 * Covers [ScheduledPromptExecutor.materialize]:
 * - creates exactly ONE Case carrying scheduledPromptId
 * - transitions the Run to [RunStatus.TARGETING] (never RUNNING)
 * - the injected first message starts with "@scheduler-loop "
 * - ADMIN permission granted on the created case
 *
 * Uses in-memory repositories and MockK stubs - no Spring context, no Neo4j.
 * Fixed clock: 2026-01-01 09:00:00 UTC.
 */
class ScheduledPromptExecutorUnitSpec : StringSpec() {

    private val nowInstant = Instant.parse("2026-01-01T09:00:00Z")
    private val clock = Clock.fixed(nowInstant, ZoneOffset.UTC)

    private val namespaceId: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val agentId: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
    private val promptTemplateId: UUID = UUID.fromString("30000000-0000-0000-0000-000000000001")
    private val creatorId: UUID = UUID.fromString("40000000-0000-0000-0000-000000000001")
    private val caseId: UUID = UUID.fromString("50000000-0000-0000-0000-000000000001")

    private val properties = SchedulerProperties(batchSize = 5, leaseMinutes = 30L)

    private val creator = User(
        metadata = EntityMetadata(id = creatorId),
        externalId = "creator@example.com",
        firstname = "Alice",
        lastname = "Smith",
    )

    private fun makeScheduledPrompt(
        loopConfig: com.fasterxml.jackson.databind.JsonNode? = null,
    ) = ScheduledPrompt(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        namespaceId = namespaceId,
        agentConfigId = agentId,
        promptTemplateId = promptTemplateId,
        name = "Weekly Digest",
        recurrence = Recurrence(
            unit = io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit.WEEK,
            timeUtc = LocalTime.of(9, 0),
        ),
        planning = Planning(
            startDate = LocalDate.of(2026, 1, 1),
            endType = io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType.NEVER,
        ),
        nextRunAt = nowInstant,
        loopConfig = loopConfig,
    )

    private fun makeRun(sp: ScheduledPrompt) = ScheduledPromptRun(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        scheduledPromptId = sp.id,
        scheduledFor = nowInstant,
        status = RunStatus.CLAIMED,
        correlationId = "test-run",
    )

    private fun makeIdleRuntime(): CaseRuntime {
        val rt = mockk<CaseRuntime>(relaxed = true)
        every { rt.statusFlow } returns MutableStateFlow(CaseStatus.IDLE)
        return rt
    }

    private fun executor(
        runRepo: ScheduledPromptRunRepository,
        caseService: CaseService,
        permissionService: PermissionService,
        userService: UserService,
    ) = ScheduledPromptExecutor(
        scheduledPromptRepository = mockk(relaxed = true),
        runRepository = runRepo,
        userRunRepository = mockk(relaxed = true),
        userService = userService,
        permissionService = permissionService,
        caseService = caseService,
        properties = properties,
        clock = clock,
    )

    init {

        // -------------------------------------------------------------------------
        // materialize: single Case created, carrying scheduledPromptId
        // -------------------------------------------------------------------------

        "materialize creates exactly ONE case carrying scheduledPromptId" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userService = mockk<UserService>().also {
                every { it.findById(creatorId) } returns creator
            }
            val createdCase = Case(metadata = EntityMetadata(id = caseId), namespaceId = namespaceId)
            val caseSlot = slot<Case>()
            val caseService = mockk<CaseService>(relaxed = true).also {
                every { it.create(capture(caseSlot)) } returns createdCase
                every { it.findActiveRuntime(caseId) } returns makeIdleRuntime()
            }

            executor(
                runRepo = runRepo,
                caseService = caseService,
                permissionService = mockk(relaxed = true),
                userService = userService,
            ).materialize(run, sp, creatorId)

            verify(exactly = 1) { caseService.create(any()) }
            caseSlot.captured.scheduledPromptId shouldBe sp.id
        }

        // -------------------------------------------------------------------------
        // materialize: transitions Run to TARGETING (never RUNNING)
        // -------------------------------------------------------------------------

        "materialize transitions run to TARGETING, not RUNNING" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userService = mockk<UserService>().also {
                every { it.findById(creatorId) } returns creator
            }
            val createdCase = Case(metadata = EntityMetadata(id = caseId), namespaceId = namespaceId)
            val caseService = mockk<CaseService>(relaxed = true).also {
                every { it.create(any()) } returns createdCase
                every { it.findActiveRuntime(caseId) } returns makeIdleRuntime()
            }

            executor(
                runRepo = runRepo,
                caseService = caseService,
                permissionService = mockk(relaxed = true),
                userService = userService,
            ).materialize(run, sp, creatorId)

            val updatedRun = runRepo.all().first { it.id == run.id }
            updatedRun.status shouldBe RunStatus.TARGETING
        }

        // -------------------------------------------------------------------------
        // materialize: first message starts with "@scheduler-loop "
        // -------------------------------------------------------------------------

        "injected first message starts with @scheduler-loop" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userService = mockk<UserService>().also {
                every { it.findById(creatorId) } returns creator
            }
            val createdCase = Case(metadata = EntityMetadata(id = caseId), namespaceId = namespaceId)
            val contentSlot = slot<List<MessageContent>>()
            val caseService = mockk<CaseService>(relaxed = true).also {
                every { it.create(any()) } returns createdCase
                every { it.findActiveRuntime(caseId) } returns makeIdleRuntime()
                every {
                    it.addMessage(caseId = caseId, actor = any(), content = capture(contentSlot))
                } returns mockk(relaxed = true)
            }

            executor(
                runRepo = runRepo,
                caseService = caseService,
                permissionService = mockk(relaxed = true),
                userService = userService,
            ).materialize(run, sp, creatorId)

            val text = contentSlot.captured.filterIsInstance<MessageContent.Text>().first().content
            text shouldStartWith "@${ScheduledPromptExecutor.LOOP_AGENT_NAME} "
        }

        // -------------------------------------------------------------------------
        // materialize: ADMIN permission granted on the created case
        // -------------------------------------------------------------------------

        "materialize grants creator ADMIN on the created case" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userService = mockk<UserService>().also {
                every { it.findById(creatorId) } returns creator
            }
            val createdCase = Case(metadata = EntityMetadata(id = caseId), namespaceId = namespaceId)
            val caseService = mockk<CaseService>(relaxed = true).also {
                every { it.create(any()) } returns createdCase
                every { it.findActiveRuntime(caseId) } returns makeIdleRuntime()
            }
            val permissionService = mockk<PermissionService>(relaxed = true)

            executor(
                runRepo = runRepo,
                caseService = caseService,
                permissionService = permissionService,
                userService = userService,
            ).materialize(run, sp, creatorId)

            verify(exactly = 1) {
                permissionService.grantPermission(
                    creatorId.toString(),
                    EntityType.CASE,
                    caseId.toString(),
                    PermissionRelation.ADMIN,
                )
            }
        }
    }
}
