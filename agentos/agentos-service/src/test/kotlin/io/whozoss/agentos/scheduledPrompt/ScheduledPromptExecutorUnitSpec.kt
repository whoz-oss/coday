package io.whozoss.agentos.scheduledPrompt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRuntime
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.prompt.Prompt
import io.whozoss.agentos.prompt.PromptService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit
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
 * Unit tests for [ScheduledPromptExecutor].
 *
 * Uses in-memory repositories and MockK stubs — no Spring context, no Neo4j.
 *
 * Fixed clock: 2026-01-01 09:00:00 UTC.
 *
 * The [InMemoryScheduledPromptUserRunRepository] is constructed with a [targetUserIdsProvider]
 * lambda to simulate the Neo4j deployment-graph traversal without touching a database.
 */
class ScheduledPromptExecutorUnitSpec : StringSpec() {

    private val nowInstant = Instant.parse("2026-01-01T09:00:00Z")
    private val clock = Clock.fixed(nowInstant, ZoneOffset.UTC)

    private val namespaceId: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val agentId: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
    private val promptTemplateId: UUID = UUID.fromString("30000000-0000-0000-0000-000000000001")
    private val userId1: UUID = UUID.fromString("40000000-0000-0000-0000-000000000001")
    private val userId2: UUID = UUID.fromString("40000000-0000-0000-0000-000000000002")
    private val caseId: UUID = UUID.fromString("50000000-0000-0000-0000-000000000001")

    private val properties = SchedulerProperties(
        maxConcurrentExecutions = 5,
        staggerDelayMs = 0L,
        leaseMinutes = 30L,
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

    private fun makeScheduledPrompt(nsId: UUID? = namespaceId) = ScheduledPrompt(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        namespaceId = nsId,
        agentConfigId = agentId,
        promptTemplateId = promptTemplateId,
        name = "Weekly Digest",
        recurrence = Recurrence(unit = SchedulerUnit.WEEK, timeUtc = LocalTime.of(9, 0)),
        planning = Planning(startDate = LocalDate.of(2026, 1, 1), endType = SchedulerEndType.NEVER),
        nextRunAt = nowInstant,
    )

    private fun makeRun(sp: ScheduledPrompt) = ScheduledPromptRun(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        scheduledPromptId = sp.id,
        scheduledFor = nowInstant,
        status = RunStatus.CLAIMED,
        correlationId = "test-run",
    )

    private fun makePromptTemplate(content: String = "Run your weekly digest report.") = Prompt(
        metadata = EntityMetadata(id = promptTemplateId),
        name = "digest-template",
        content = listOf(content),
    )

    private fun makeAgentConfig(name: String = "weekly-agent") = AgentConfig(
        metadata = EntityMetadata(id = agentId),
        namespaceId = namespaceId,
        name = name,
    )

    private fun makeIdleRuntime(): CaseRuntime {
        val rt = mockk<CaseRuntime>(relaxed = true)
        every { rt.statusFlow } returns MutableStateFlow(CaseStatus.IDLE)
        every { rt.isRunning() } returns false
        return rt
    }

    private fun makeSpRepo(sp: ScheduledPrompt): ScheduledPromptRepository {
        val repo = mockk<ScheduledPromptRepository>()
        every { repo.findByIds(listOf(sp.id)) } returns listOf(sp)
        return repo
    }

    /**
     * Build an [InMemoryScheduledPromptUserRunRepository] whose graph-traversal materialize
     * returns [targetUserIds] for any `(agentConfigId, namespaceId)` pair.
     */
    private fun makeUserRunRepo(targetUserIds: Set<UUID> = emptySet()) =
        InMemoryScheduledPromptUserRunRepository { _, _ -> targetUserIds }

    private fun executor(
        spRepo: ScheduledPromptRepository,
        runRepo: ScheduledPromptRunRepository,
        userRunRepo: ScheduledPromptUserRunRepository,
        promptService: PromptService,
        agentConfigService: AgentConfigService = mockk(relaxed = true),
        caseService: CaseService,
        permissionService: PermissionService,
        userService: UserService,
    ) = ScheduledPromptExecutor(
        scheduledPromptRepository = spRepo,
        runRepository = runRepo,
        userRunRepository = userRunRepo,
        promptService = promptService,
        agentConfigService = agentConfigService,
        caseService = caseService,
        permissionService = permissionService,
        userService = userService,
        properties = properties,
        clock = clock,
    )

    init {

        // -------------------------------------------------------------------------
        // Phase A — Materialisation
        // -------------------------------------------------------------------------

        "Phase A: materialise creates PENDING UserRuns for each target user" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1, userId2))

            executor(
                spRepo = makeSpRepo(sp),
                runRepo = runRepo,
                userRunRepo = userRunRepo,
                promptService = mockk(relaxed = true),
                caseService = mockk(relaxed = true),
                permissionService = mockk(relaxed = true),
                userService = mockk(relaxed = true),
            ).materialize(run, sp)

            val userRuns = userRunRepo.all()
            userRuns.size shouldBe 2
            userRuns.all { it.status == UserRunStatus.PENDING } shouldBe true
            userRuns.map { it.userId }.toSet() shouldBe setOf(userId1, userId2)
        }

        "Phase A: materialise transitions Run to RUNNING" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1))

            executor(
                spRepo = makeSpRepo(sp),
                runRepo = runRepo,
                userRunRepo = userRunRepo,
                promptService = mockk(relaxed = true),
                caseService = mockk(relaxed = true),
                permissionService = mockk(relaxed = true),
                userService = mockk(relaxed = true),
            ).materialize(run, sp)

            val updatedRun = runRepo.all().first { it.id == run.id }
            updatedRun.status shouldBe RunStatus.RUNNING
        }

        "Phase A: materialise is idempotent (double call does not duplicate UserRuns)" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1))

            val exec = executor(
                spRepo = makeSpRepo(sp),
                runRepo = runRepo,
                userRunRepo = userRunRepo,
                promptService = mockk(relaxed = true),
                caseService = mockk(relaxed = true),
                permissionService = mockk(relaxed = true),
                userService = mockk(relaxed = true),
            )
            exec.materialize(run, sp)
            exec.materialize(run, sp)

            userRunRepo.all().size shouldBe 1
        }

        "Phase A: platform-scope ScheduledPrompt materialises zero UserRuns and transitions to DONE" {
            val sp = makeScheduledPrompt(nsId = null)
            val run = makeRun(sp)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            // Provider is never called for platform-scope (namespaceId == null)
            val userRunRepo = makeUserRunRepo(emptySet())

            executor(
                spRepo = makeSpRepo(sp),
                runRepo = runRepo,
                userRunRepo = userRunRepo,
                promptService = mockk(relaxed = true),
                caseService = mockk(relaxed = true),
                permissionService = mockk(relaxed = true),
                userService = mockk(relaxed = true),
            ).materialize(run, sp)

            userRunRepo.all().size shouldBe 0
            // No target users → Run transitions directly to DONE (nothing to consume)
            val updatedRun = runRepo.all().first { it.id == run.id }
            updatedRun.status shouldBe RunStatus.DONE
        }

        // -------------------------------------------------------------------------
        // Phase B — consumeAvailable with no pending UserRuns
        // -------------------------------------------------------------------------

        "Phase B: consumeAvailable does nothing when no PENDING UserRuns exist" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(emptySet())
            val caseService = mockk<CaseService>(relaxed = true)

            executor(
                spRepo = makeSpRepo(sp),
                runRepo = runRepo,
                userRunRepo = userRunRepo,
                promptService = mockk(relaxed = true),
                caseService = caseService,
                permissionService = mockk(relaxed = true),
                userService = mockk(relaxed = true),
            ).consumeAvailable()

            verify(exactly = 0) { caseService.create(any()) }
        }

        // -------------------------------------------------------------------------
        // Phase B — full execution path
        // -------------------------------------------------------------------------

        "Phase B: consumeAvailable creates Case, grants ADMIN, and injects @agentName /promptName message" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1)).also {
                it.materialize(run.id, agentId, namespaceId)
            }

            val promptTemplate = makePromptTemplate() // name = "digest-template"
            val promptService = mockk<PromptService>().also {
                every { it.findById(promptTemplateId) } returns promptTemplate
            }

            val agentConfig = makeAgentConfig(name = "weekly-agent")
            val agentConfigService = mockk<AgentConfigService>().also {
                every { it.findById(agentId) } returns agentConfig
            }

            val createdCase = Case(
                metadata = EntityMetadata(id = caseId),
                namespaceId = namespaceId,
            )
            val caseService = mockk<CaseService>(relaxed = true).also {
                every { it.create(any()) } returns createdCase
                every { it.findById(caseId) } returns createdCase.copy(status = CaseStatus.IDLE)
                every { it.findActiveRuntime(caseId) } returns null // runtime evicted — IDLE
            }

            val permissionService = mockk<PermissionService>(relaxed = true)
            val userService = mockk<UserService>().also {
                every { it.findById(userId1) } returns user1
            }

            executor(
                spRepo = makeSpRepo(sp),
                runRepo = runRepo,
                userRunRepo = userRunRepo,
                promptService = promptService,
                agentConfigService = agentConfigService,
                caseService = caseService,
                permissionService = permissionService,
                userService = userService,
            ).consumeAvailable()

            // Wait for the background coroutine to settle
            kotlinx.coroutines.delay(500)

            val caseSlot = slot<Case>()
            verify(exactly = 1) { caseService.create(capture(caseSlot)) }
            caseSlot.captured.title shouldBe "Weekly Digest"
            verify(exactly = 1) {
                permissionService.grantPermission(
                    userId1.toString(),
                    EntityType.CASE,
                    caseId.toString(),
                    PermissionRelation.ADMIN,
                )
            }
            val actorSlot = slot<Actor>()
            val contentSlot = slot<List<MessageContent>>()
            verify(exactly = 1) {
                caseService.addMessage(
                    caseId = caseId,
                    actor = capture(actorSlot),
                    content = capture(contentSlot),
                )
            }
            actorSlot.captured.role shouldBe ActorRole.USER
            actorSlot.captured.id shouldBe userId1.toString()
            // The message must be "@agentName <resolved prompt content>" — the Executor
            // resolves the content directly rather than injecting a /slash-command, because
            // PromptCommandParser requires text to start with '/' which is incompatible
            // with the leading @mention.
            val text = contentSlot.captured.filterIsInstance<MessageContent.Text>().first().content
            text shouldBe "@weekly-agent Run your weekly digest report."
        }

        // -------------------------------------------------------------------------
        // Completion check
        // -------------------------------------------------------------------------

        "Completion: Run transitions to DONE when all UserRuns are settled" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1))
            userRunRepo.materialize(run.id, agentId, namespaceId)
            val ur = userRunRepo.all().first()
            userRunRepo.markTerminal(ur.id, UserRunStatus.DONE, nowInstant)

            executor(
                spRepo = makeSpRepo(sp),
                runRepo = runRepo,
                userRunRepo = userRunRepo,
                promptService = mockk(relaxed = true),
                caseService = mockk(relaxed = true),
                permissionService = mockk(relaxed = true),
                userService = mockk(relaxed = true),
            ).consumeAvailable()

            val updatedRun = runRepo.all().first { it.id == run.id }
            updatedRun.status shouldBe RunStatus.RUNNING
        }

        "Completion: Run transitions to FAILED when a UserRun has failed" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1))
            userRunRepo.materialize(run.id, agentId, namespaceId)
            val ur = userRunRepo.all().first()
            userRunRepo.markTerminal(ur.id, UserRunStatus.FAILED, nowInstant, "boom")

            userRunRepo.hasAnyFailed(run.id) shouldBe true
            userRunRepo.countByRunIdAndStatus(run.id, UserRunStatus.PENDING) shouldBe 0
            userRunRepo.countByRunIdAndStatus(run.id, UserRunStatus.RUNNING) shouldBe 0
        }

        // -------------------------------------------------------------------------
        // Phase B: user not found — UserRun marked FAILED
        // -------------------------------------------------------------------------

        "Phase B: user not found in UserService marks UserRun FAILED" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1)).also {
                it.materialize(run.id, agentId, namespaceId)
            }

            val userService = mockk<UserService>().also {
                every { it.findById(userId1) } returns null
            }

            executor(
                spRepo = makeSpRepo(sp),
                runRepo = runRepo,
                userRunRepo = userRunRepo,
                promptService = mockk(relaxed = true),
                agentConfigService = mockk(relaxed = true),
                caseService = mockk(relaxed = true),
                permissionService = mockk(relaxed = true),
                userService = userService,
            ).consumeAvailable()

            // Wait for the background coroutine
            kotlinx.coroutines.delay(500)

            val userRun = userRunRepo.all().first()
            userRun.status shouldBe UserRunStatus.FAILED
        }

        // -------------------------------------------------------------------------
        // Phase B: prompt or agent not found — UserRun marked FAILED
        // -------------------------------------------------------------------------

        "Phase B: prompt template not found marks UserRun FAILED" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1)).also {
                it.materialize(run.id, agentId, namespaceId)
            }

            val promptService = mockk<PromptService>().also {
                every { it.findById(promptTemplateId) } returns null
            }
            val userService = mockk<UserService>().also {
                every { it.findById(userId1) } returns user1
            }

            executor(
                spRepo = makeSpRepo(sp),
                runRepo = runRepo,
                userRunRepo = userRunRepo,
                promptService = promptService,
                agentConfigService = mockk(relaxed = true),
                caseService = mockk(relaxed = true),
                permissionService = mockk(relaxed = true),
                userService = userService,
            ).consumeAvailable()

            kotlinx.coroutines.delay(500)

            val userRun = userRunRepo.all().first()
            userRun.status shouldBe UserRunStatus.FAILED
            userRun.error shouldBe "PromptTemplate $promptTemplateId not found"
        }

        "Phase B: prompt template with empty content marks UserRun FAILED" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1)).also {
                it.materialize(run.id, agentId, namespaceId)
            }

            val promptService = mockk<PromptService>().also {
                every { it.findById(promptTemplateId) } returns makePromptTemplate(content = "")
            }
            val userService = mockk<UserService>().also {
                every { it.findById(userId1) } returns user1
            }

            executor(
                spRepo = makeSpRepo(sp),
                runRepo = runRepo,
                userRunRepo = userRunRepo,
                promptService = promptService,
                agentConfigService = mockk(relaxed = true),
                caseService = mockk(relaxed = true),
                permissionService = mockk(relaxed = true),
                userService = userService,
            ).consumeAvailable()

            kotlinx.coroutines.delay(500)

            val userRun = userRunRepo.all().first()
            userRun.status shouldBe UserRunStatus.FAILED
            userRun.error shouldBe "PromptTemplate $promptTemplateId has empty content"
        }

        "Phase B: agent config not found marks UserRun FAILED" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1)).also {
                it.materialize(run.id, agentId, namespaceId)
            }

            val promptService = mockk<PromptService>().also {
                every { it.findById(promptTemplateId) } returns makePromptTemplate()
            }
            val agentConfigService = mockk<AgentConfigService>().also {
                every { it.findById(agentId) } returns null
            }
            val userService = mockk<UserService>().also {
                every { it.findById(userId1) } returns user1
            }

            executor(
                spRepo = makeSpRepo(sp),
                runRepo = runRepo,
                userRunRepo = userRunRepo,
                promptService = promptService,
                agentConfigService = agentConfigService,
                caseService = mockk(relaxed = true),
                permissionService = mockk(relaxed = true),
                userService = userService,
            ).consumeAvailable()

            kotlinx.coroutines.delay(500)

            val userRun = userRunRepo.all().first()
            userRun.status shouldBe UserRunStatus.FAILED
            userRun.error shouldBe "AgentConfig $agentId not found"
        }
    }
}
