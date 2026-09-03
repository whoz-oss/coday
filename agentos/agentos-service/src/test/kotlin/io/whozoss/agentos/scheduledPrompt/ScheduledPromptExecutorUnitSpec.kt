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
import io.whozoss.agentos.sdk.scheduledPrompt.UserContextProvider
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
 * Phase B tests call [ScheduledPromptExecutor.processUserRun] directly (`internal`)
 * rather than starting the full lifecycle loop. This keeps the tests fast and
 * deterministic without coroutine infrastructure.
 *
 * The [InMemoryScheduledPromptUserRunRepository] is constructed with a [targetUserIdsProvider]
 * lambda to simulate the Neo4j deployment-graph traversal without touching a database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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
        batchSize = 5,
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
        userContextProvider: UserContextProvider? = null,
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
        userContextProvider = userContextProvider,
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

        "Phase A: materialise propagates exception from userRunRepository — Run stays CLAIMED" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }

            val throwingUserRunRepo = mockk<ScheduledPromptUserRunRepository>().also {
                every { it.materialize(run.id, agentId, namespaceId) } throws RuntimeException("Simulated Neo4j failure")
            }

            val exec = executor(
                spRepo = makeSpRepo(sp),
                runRepo = runRepo,
                userRunRepo = throwingUserRunRepo,
                promptService = mockk(relaxed = true),
                caseService = mockk(relaxed = true),
                permissionService = mockk(relaxed = true),
                userService = mockk(relaxed = true),
            )

            io.kotest.assertions.throwables.shouldThrow<RuntimeException> {
                exec.materialize(run, sp)
            }

            val updatedRun = runRepo.all().first { it.id == run.id }
            updatedRun.status shouldBe RunStatus.CLAIMED
        }

        "Phase A: platform-scope ScheduledPrompt materialises zero UserRuns and transitions to DONE" {
            val sp = makeScheduledPrompt(nsId = null)
            val run = makeRun(sp)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
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
            val updatedRun = runRepo.all().first { it.id == run.id }
            updatedRun.status shouldBe RunStatus.DONE
        }

        // -------------------------------------------------------------------------
        // Phase B — processUserRun (called directly, internal)
        // -------------------------------------------------------------------------

        "Phase B: processUserRun creates Case, grants ADMIN, and injects @agentName message" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1)).also {
                it.materialize(run.id, agentId, namespaceId)
            }
            val userRun = userRunRepo.all().first()
            // Transition to RUNNING so processUserRun can mark it terminal
            userRunRepo.claimBatch(java.time.Duration.ofMinutes(30), 10)

            val promptService = mockk<PromptService>().also {
                every { it.findById(promptTemplateId) } returns makePromptTemplate()
            }
            val agentConfigService = mockk<AgentConfigService>().also {
                every { it.findById(agentId) } returns makeAgentConfig(name = "weekly-agent")
            }
            val createdCase = Case(
                metadata = EntityMetadata(id = caseId),
                namespaceId = namespaceId,
            )
            val caseService = mockk<CaseService>(relaxed = true).also {
                every { it.create(any()) } returns createdCase
                every { it.findById(caseId) } returns createdCase.copy(status = CaseStatus.IDLE)
                every { it.findActiveRuntime(caseId) } returns null
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
            ).processUserRun(userRun)

            val caseSlot = slot<Case>()
            verify(exactly = 1) { caseService.create(capture(caseSlot)) }
            caseSlot.captured.title shouldBe "Weekly Digest ${nowInstant}"
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
            val text = contentSlot.captured.filterIsInstance<MessageContent.Text>().first().content
            text shouldBe "@weekly-agent Run your weekly digest report."
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
            val userRun = userRunRepo.claimBatch(java.time.Duration.ofMinutes(30), 10).first()

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
            ).processUserRun(userRun)

            val updated = userRunRepo.all().first { it.id == userRun.id }
            updated.status shouldBe UserRunStatus.FAILED
            updated.error shouldBe "PromptTemplate $promptTemplateId not found"
        }

        "Phase B: prompt template with empty content marks UserRun FAILED" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1)).also {
                it.materialize(run.id, agentId, namespaceId)
            }
            val userRun = userRunRepo.claimBatch(java.time.Duration.ofMinutes(30), 10).first()

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
            ).processUserRun(userRun)

            val updated = userRunRepo.all().first { it.id == userRun.id }
            updated.status shouldBe UserRunStatus.FAILED
            updated.error shouldBe "PromptTemplate $promptTemplateId has empty content"
        }

        // -------------------------------------------------------------------------
        // Phase B — monitorLaunch via statusFlow
        // -------------------------------------------------------------------------

        "Phase B: monitorLaunch closes UserRun as DONE when runtime reaches IDLE" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1)).also {
                it.materialize(run.id, agentId, namespaceId)
            }
            val userRun = userRunRepo.claimBatch(java.time.Duration.ofMinutes(30), 10).first()

            val promptService = mockk<PromptService>().also {
                every { it.findById(promptTemplateId) } returns makePromptTemplate()
            }
            val agentConfigService = mockk<AgentConfigService>().also {
                every { it.findById(agentId) } returns makeAgentConfig()
            }
            val userService = mockk<UserService>().also {
                every { it.findById(userId1) } returns user1
            }
            val createdCase = Case(metadata = EntityMetadata(id = caseId), namespaceId = namespaceId)
            val runtime = mockk<CaseRuntime>(relaxed = true).also {
                every { it.statusFlow } returns MutableStateFlow(CaseStatus.IDLE)
            }
            val caseService = mockk<CaseService>(relaxed = true).also {
                every { it.create(any()) } returns createdCase
                every { it.findActiveRuntime(caseId) } returns runtime
            }

            executor(
                spRepo = makeSpRepo(sp),
                runRepo = runRepo,
                userRunRepo = userRunRepo,
                promptService = promptService,
                agentConfigService = agentConfigService,
                caseService = caseService,
                permissionService = mockk(relaxed = true),
                userService = userService,
            ).processUserRun(userRun)

            userRunRepo.all().first { it.id == userRun.id }.status shouldBe UserRunStatus.DONE
        }

        "Phase B: monitorLaunch closes UserRun as FAILED when runtime reaches ERROR" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1)).also {
                it.materialize(run.id, agentId, namespaceId)
            }
            val userRun = userRunRepo.claimBatch(java.time.Duration.ofMinutes(30), 10).first()

            val promptService = mockk<PromptService>().also {
                every { it.findById(promptTemplateId) } returns makePromptTemplate()
            }
            val agentConfigService = mockk<AgentConfigService>().also {
                every { it.findById(agentId) } returns makeAgentConfig()
            }
            val userService = mockk<UserService>().also {
                every { it.findById(userId1) } returns user1
            }
            val createdCase = Case(metadata = EntityMetadata(id = caseId), namespaceId = namespaceId)
            val runtime = mockk<CaseRuntime>(relaxed = true).also {
                every { it.statusFlow } returns MutableStateFlow(CaseStatus.ERROR)
            }
            val caseService = mockk<CaseService>(relaxed = true).also {
                every { it.create(any()) } returns createdCase
                every { it.findActiveRuntime(caseId) } returns runtime
            }

            executor(
                spRepo = makeSpRepo(sp),
                runRepo = runRepo,
                userRunRepo = userRunRepo,
                promptService = promptService,
                agentConfigService = agentConfigService,
                caseService = caseService,
                permissionService = mockk(relaxed = true),
                userService = userService,
            ).processUserRun(userRun)

            val updated = userRunRepo.all().first { it.id == userRun.id }
            updated.status shouldBe UserRunStatus.FAILED
            updated.error shouldBe "Case reached terminal status ERROR"
        }

        "Phase B: monitorLaunch closes UserRun as TIMEOUT on timeout (Case still RUNNING)" {
            val shortTimeoutProperties = SchedulerProperties(
                batchSize = 5,
                launchTimeoutSeconds = 0L,
                leaseMinutes = 30L,
            )

            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1)).also {
                it.materialize(run.id, agentId, namespaceId)
            }
            val userRun = userRunRepo.claimBatch(java.time.Duration.ofMinutes(30), 10).first()

            val promptService = mockk<PromptService>().also {
                every { it.findById(promptTemplateId) } returns makePromptTemplate()
            }
            val agentConfigService = mockk<AgentConfigService>().also {
                every { it.findById(agentId) } returns makeAgentConfig()
            }
            val userService = mockk<UserService>().also {
                every { it.findById(userId1) } returns user1
            }
            val createdCase = Case(metadata = EntityMetadata(id = caseId), namespaceId = namespaceId)
            val statusFlow = MutableStateFlow(CaseStatus.RUNNING)
            val runtime = mockk<CaseRuntime>(relaxed = true).also {
                every { it.statusFlow } returns statusFlow
            }
            val caseService = mockk<CaseService>(relaxed = true).also {
                every { it.create(any()) } returns createdCase
                every { it.findActiveRuntime(caseId) } returns runtime
            }

            ScheduledPromptExecutor(
                scheduledPromptRepository = makeSpRepo(sp),
                runRepository = runRepo,
                userRunRepository = userRunRepo,
                promptService = promptService,
                agentConfigService = agentConfigService,
                caseService = caseService,
                permissionService = mockk(relaxed = true),
                userService = userService,
                properties = shortTimeoutProperties,
                clock = clock,
            ).processUserRun(userRun)

            val updated = userRunRepo.all().first { it.id == userRun.id }
            updated.status shouldBe UserRunStatus.TIMEOUT
        }

        // -------------------------------------------------------------------------
        // Phase B — scheduledPromptId propagation
        // -------------------------------------------------------------------------

        "Phase B: Case created by ScheduledPrompt carries scheduledPromptId" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1)).also {
                it.materialize(run.id, agentId, namespaceId)
            }
            val userRun = userRunRepo.claimBatch(java.time.Duration.ofMinutes(30), 10).first()

            val promptService = mockk<PromptService>().also {
                every { it.findById(promptTemplateId) } returns makePromptTemplate()
            }
            val agentConfigService = mockk<AgentConfigService>().also {
                every { it.findById(agentId) } returns makeAgentConfig()
            }
            val userService = mockk<UserService>().also {
                every { it.findById(userId1) } returns user1
            }
            val createdCase = Case(metadata = EntityMetadata(id = caseId), namespaceId = namespaceId)
            val caseSlot = slot<Case>()
            val caseService = mockk<CaseService>(relaxed = true).also {
                every { it.create(capture(caseSlot)) } returns createdCase
                every { it.findActiveRuntime(caseId) } returns null
                every { it.findById(caseId) } returns createdCase.copy(status = CaseStatus.IDLE)
            }

            executor(
                spRepo = makeSpRepo(sp),
                runRepo = runRepo,
                userRunRepo = userRunRepo,
                promptService = promptService,
                agentConfigService = agentConfigService,
                caseService = caseService,
                permissionService = mockk(relaxed = true),
                userService = userService,
            ).processUserRun(userRun)

            caseSlot.captured.scheduledPromptId shouldBe sp.id
        }

        // -------------------------------------------------------------------------
        // Phase B — UserContextProvider enrichment
        // -------------------------------------------------------------------------

        "Phase B: sessionContext from UserContextProvider is forwarded to addMessage" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1)).also {
                it.materialize(run.id, agentId, namespaceId)
            }
            val userRun = userRunRepo.claimBatch(java.time.Duration.ofMinutes(30), 10).first()

            val promptService = mockk<PromptService>().also {
                every { it.findById(promptTemplateId) } returns makePromptTemplate()
            }
            val agentConfigService = mockk<AgentConfigService>().also {
                every { it.findById(agentId) } returns makeAgentConfig()
            }
            val userService = mockk<UserService>().also {
                every { it.findById(userId1) } returns user1
            }
            val createdCase = Case(metadata = EntityMetadata(id = caseId), namespaceId = namespaceId)
            val caseService = mockk<CaseService>(relaxed = true).also {
                every { it.create(any()) } returns createdCase
                every { it.findActiveRuntime(caseId) } returns null
                every { it.findById(caseId) } returns createdCase.copy(status = CaseStatus.IDLE)
            }
            val expectedContext = mapOf("userContext" to mapOf("talentId" to "t1"))
            val provider = mockk<UserContextProvider>().also {
                every { it.provideUserContext(user1.externalId, namespaceId) } returns expectedContext
            }

            executor(
                spRepo = makeSpRepo(sp),
                runRepo = runRepo,
                userRunRepo = userRunRepo,
                promptService = promptService,
                agentConfigService = agentConfigService,
                caseService = caseService,
                permissionService = mockk(relaxed = true),
                userService = userService,
                userContextProvider = provider,
            ).processUserRun(userRun)

            val sessionContextSlot = slot<Map<String, Any?>>()
            verify(exactly = 1) {
                caseService.addMessage(
                    caseId = caseId,
                    actor = any(),
                    content = any(),
                    sessionContext = capture(sessionContextSlot),
                )
            }
            sessionContextSlot.captured shouldBe expectedContext
        }

        "Phase B: addMessage is called with null sessionContext when UserContextProvider throws" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1)).also {
                it.materialize(run.id, agentId, namespaceId)
            }
            val userRun = userRunRepo.claimBatch(java.time.Duration.ofMinutes(30), 10).first()

            val promptService = mockk<PromptService>().also {
                every { it.findById(promptTemplateId) } returns makePromptTemplate()
            }
            val agentConfigService = mockk<AgentConfigService>().also {
                every { it.findById(agentId) } returns makeAgentConfig()
            }
            val userService = mockk<UserService>().also {
                every { it.findById(userId1) } returns user1
            }
            val createdCase = Case(metadata = EntityMetadata(id = caseId), namespaceId = namespaceId)
            val caseService = mockk<CaseService>(relaxed = true).also {
                every { it.create(any()) } returns createdCase
                every { it.findActiveRuntime(caseId) } returns null
                every { it.findById(caseId) } returns createdCase.copy(status = CaseStatus.IDLE)
            }
            val provider = mockk<UserContextProvider>().also {
                every { it.provideUserContext(any(), any()) } throws RuntimeException("Copilot unreachable")
            }

            executor(
                spRepo = makeSpRepo(sp),
                runRepo = runRepo,
                userRunRepo = userRunRepo,
                promptService = promptService,
                agentConfigService = agentConfigService,
                caseService = caseService,
                permissionService = mockk(relaxed = true),
                userService = userService,
                userContextProvider = provider,
            ).processUserRun(userRun)

            val updated = userRunRepo.all().first { it.id == userRun.id }
            updated.status shouldBe UserRunStatus.DONE
            verify(exactly = 1) {
                caseService.addMessage(
                    caseId = caseId,
                    actor = any(),
                    content = any(),
                    sessionContext = null,
                )
            }
        }

        // -------------------------------------------------------------------------
        // Phase B — error handling in processUserRun
        // -------------------------------------------------------------------------

        "processUserRun: resolveContext throws (findById returns null for run) → markFailed called" {
            // runRepository.findById returns null → resolveContext throws IllegalStateException
            // → catch block in processUserRun calls markFailed → userRunRepository.markTerminal FAILED
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val userRunRepo = makeUserRunRepo(setOf(userId1)).also {
                it.materialize(run.id, agentId, namespaceId)
            }
            val userRun = userRunRepo.claimBatch(java.time.Duration.ofMinutes(30), 10).first()

            // runRepository that throws on findById
            val throwingRunRepo = mockk<ScheduledPromptRunRepository>().also {
                every { it.findById(run.id) } throws RuntimeException("DB unavailable")
            }

            executor(
                spRepo = makeSpRepo(sp),
                runRepo = throwingRunRepo,
                userRunRepo = userRunRepo,
                promptService = mockk(relaxed = true),
                agentConfigService = mockk(relaxed = true),
                caseService = mockk(relaxed = true),
                permissionService = mockk(relaxed = true),
                userService = mockk(relaxed = true),
            ).processUserRun(userRun)

            val updated = userRunRepo.all().first { it.id == userRun.id }
            updated.status shouldBe UserRunStatus.FAILED
        }

        "processUserRun: markFailed fails (DB down) → exception swallowed, no rethrow" {
            // Both resolveContext (findById) and markTerminal throw → processUserRun must not propagate
            val sp = makeScheduledPrompt()
            val userRunRepo = makeUserRunRepo(setOf(userId1)).also {
                it.materialize(UUID.randomUUID(), agentId, namespaceId)
            }
            // Claim a userRun from the in-memory repo so we have a valid id
            val userRun = userRunRepo.claimBatch(java.time.Duration.ofMinutes(30), 10).first()

            // runRepository throws → resolveContext throws → catch block → markFailed called
            val throwingRunRepo = mockk<ScheduledPromptRunRepository>().also {
                every { it.findById(any()) } throws RuntimeException("DB unavailable")
            }
            // userRunRepository also throws on markTerminal → markFailed swallows the error
            val throwingUserRunRepo = mockk<ScheduledPromptUserRunRepository>().also {
                every { it.markTerminal(any(), any(), any(), any()) } throws RuntimeException("DB also down")
            }

            // Must not throw — the exception in markFailed is swallowed by runCatching in markFailed
            executor(
                spRepo = makeSpRepo(sp),
                runRepo = throwingRunRepo,
                userRunRepo = throwingUserRunRepo,
                promptService = mockk(relaxed = true),
                agentConfigService = mockk(relaxed = true),
                caseService = mockk(relaxed = true),
                permissionService = mockk(relaxed = true),
                userService = mockk(relaxed = true),
            ).processUserRun(userRun)
            // No assertion needed — the test passes if no exception is thrown
        }

        "processUserRun: markTerminal fails after successful case creation → UserRun stays RUNNING (at-least-once)" {
            // resolveContext and createAndInjectCase succeed, but markTerminal (called from closeUserRun)
            // throws — the UserRun stays RUNNING (lease expires and is reclaimed: at-least-once delivery)
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val realRunRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1)).also {
                it.materialize(run.id, agentId, namespaceId)
            }
            val userRun = userRunRepo.claimBatch(java.time.Duration.ofMinutes(30), 10).first()

            val promptService = mockk<PromptService>().also {
                every { it.findById(promptTemplateId) } returns makePromptTemplate()
            }
            val agentConfigService = mockk<AgentConfigService>().also {
                every { it.findById(agentId) } returns makeAgentConfig()
            }
            val userService = mockk<UserService>().also {
                every { it.findById(userId1) } returns user1
            }
            val createdCase = Case(metadata = EntityMetadata(id = caseId), namespaceId = namespaceId)
            val caseService = mockk<CaseService>(relaxed = true).also {
                every { it.create(any()) } returns createdCase
                // No active runtime → closeUserRun is called with findById result
                every { it.findActiveRuntime(caseId) } returns null
                every { it.findById(caseId) } returns createdCase.copy(status = CaseStatus.IDLE)
            }

            // Wrap userRunRepo to make markTerminal throw on the first call (from closeUserRun)
            // but still track the actual state so we can verify the UserRun stays RUNNING
            val throwingOnMarkTerminalRepo = object : ScheduledPromptUserRunRepository by userRunRepo {
                override fun markTerminal(
                    id: UUID,
                    status: UserRunStatus,
                    now: Instant,
                    error: String?,
                ): ScheduledPromptUserRun {
                    throw RuntimeException("DB write failed")
                }
            }

            // processUserRun catches the exception from closeUserRun → calls markFailed
            // but markFailed also uses the same repo (throwingOnMarkTerminalRepo) → also throws
            // → runCatching in markFailed swallows it → no rethrow from processUserRun
            executor(
                spRepo = makeSpRepo(sp),
                runRepo = realRunRepo,
                userRunRepo = throwingOnMarkTerminalRepo,
                promptService = promptService,
                agentConfigService = agentConfigService,
                caseService = caseService,
                permissionService = mockk(relaxed = true),
                userService = userService,
            ).processUserRun(userRun)

            // The original in-memory repo still shows the UserRun as RUNNING (markTerminal was never persisted)
            val updated = userRunRepo.all().first { it.id == userRun.id }
            updated.status shouldBe UserRunStatus.RUNNING
        }

        // -------------------------------------------------------------------------
        // Consumer loop — real coroutine loop tests
        // -------------------------------------------------------------------------

        "consumer loop: N UserRuns are all processed by the real coroutine loop" {
            // Verify that the real producer/channel/worker loop processes all PENDING UserRuns.
            // Uses UnconfinedTestDispatcher so coroutines run eagerly without real threads.
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userIds = (1..10).map { UUID.randomUUID() }.toSet()
            val userRunRepo = InMemoryScheduledPromptUserRunRepository { _, _ -> userIds }
            userRunRepo.materialize(run.id, agentId, namespaceId)

            val promptService = mockk<PromptService>().also {
                every { it.findById(promptTemplateId) } returns makePromptTemplate()
            }
            val agentConfigService = mockk<AgentConfigService>().also {
                every { it.findById(agentId) } returns makeAgentConfig()
            }
            val userService = mockk<UserService>().also { svc ->
                userIds.forEach { uid ->
                    every { svc.findById(uid) } returns User(
                        metadata = EntityMetadata(id = uid),
                        externalId = "$uid@example.com",
                        firstname = "User",
                        lastname = uid.toString().take(6),
                    )
                }
            }
            // Each Case immediately reaches IDLE so processUserRun marks UserRun DONE quickly.
            val runtimeMap = mutableMapOf<UUID, CaseRuntime>()
            val caseService = mockk<CaseService>(relaxed = true).also { svc ->
                every { svc.create(any()) } answers {
                    val id = UUID.randomUUID()
                    val flow = MutableStateFlow(CaseStatus.IDLE)
                    val rt = mockk<CaseRuntime>(relaxed = true).also { every { it.statusFlow } returns flow }
                    runtimeMap[id] = rt
                    Case(metadata = EntityMetadata(id = id), namespaceId = namespaceId)
                }
                every { svc.findActiveRuntime(any()) } answers { runtimeMap[firstArg<UUID>()] }
            }

            val testDispatcher = UnconfinedTestDispatcher()
            val exec = ScheduledPromptExecutor(
                scheduledPromptRepository = makeSpRepo(sp),
                runRepository = runRepo,
                userRunRepository = userRunRepo,
                promptService = promptService,
                agentConfigService = agentConfigService,
                caseService = caseService,
                permissionService = mockk(relaxed = true),
                userService = userService,
                properties = SchedulerProperties(batchSize = 5, leaseMinutes = 30L, emptyPollDelayMs = 10L),
                clock = clock,
                dispatcher = testDispatcher,
            )

            runTest(testDispatcher) {
                exec.start()
                // Poll until all 10 UserRuns are DONE (or timeout after 5 s real time).
                withTimeout(5_000L) {
                    while (userRunRepo.all().count { it.status == UserRunStatus.DONE } < 10) {
                        kotlinx.coroutines.delay(10L)
                    }
                }
                exec.stop()
            }

            userRunRepo.all().all { it.status == UserRunStatus.DONE } shouldBe true
        }

        "consumer loop: stop() closes channel and workers exit cleanly" {
            // After stop(), isRunning() must return false and no coroutine must keep running.
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userIds = setOf(userId1, userId2)
            val userRunRepo = InMemoryScheduledPromptUserRunRepository { _, _ -> userIds }
            userRunRepo.materialize(run.id, agentId, namespaceId)

            val promptService = mockk<PromptService>().also {
                every { it.findById(promptTemplateId) } returns makePromptTemplate()
            }
            val agentConfigService = mockk<AgentConfigService>().also {
                every { it.findById(agentId) } returns makeAgentConfig()
            }
            val userService = mockk<UserService>().also { svc ->
                every { svc.findById(userId1) } returns user1
                every { svc.findById(userId2) } returns user2
            }
            val runtimeMap = mutableMapOf<UUID, CaseRuntime>()
            val caseService = mockk<CaseService>(relaxed = true).also { svc ->
                every { svc.create(any()) } answers {
                    val id = UUID.randomUUID()
                    val flow = MutableStateFlow(CaseStatus.IDLE)
                    val rt = mockk<CaseRuntime>(relaxed = true).also { every { it.statusFlow } returns flow }
                    runtimeMap[id] = rt
                    Case(metadata = EntityMetadata(id = id), namespaceId = namespaceId)
                }
                every { svc.findActiveRuntime(any()) } answers { runtimeMap[firstArg<UUID>()] }
            }

            val testDispatcher = UnconfinedTestDispatcher()
            val exec = ScheduledPromptExecutor(
                scheduledPromptRepository = makeSpRepo(sp),
                runRepository = runRepo,
                userRunRepository = userRunRepo,
                promptService = promptService,
                agentConfigService = agentConfigService,
                caseService = caseService,
                permissionService = mockk(relaxed = true),
                userService = userService,
                properties = SchedulerProperties(batchSize = 5, leaseMinutes = 30L, emptyPollDelayMs = 10L),
                clock = clock,
                dispatcher = testDispatcher,
            )

            runTest(testDispatcher) {
                exec.start()
                exec.isRunning() shouldBe true
                exec.stop()
            }

            exec.isRunning() shouldBe false
        }

        "consumer loop: worker exception does not stop other workers" {
            // A resolveContext failure on one UserRun must not prevent other UserRuns from completing.
            // We simulate this by making runRepository.findById throw for one specific runId,
            // which causes resolveContext to throw for that UserRun only.
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val normalUserIds = (1..5).map { UUID.randomUUID() }.toSet()
            val failingUserId = UUID.randomUUID()
            val allUserIds = normalUserIds + failingUserId
            val userRunRepo = InMemoryScheduledPromptUserRunRepository { _, _ -> allUserIds }
            userRunRepo.materialize(run.id, agentId, namespaceId)

            val promptService = mockk<PromptService>().also {
                every { it.findById(promptTemplateId) } returns makePromptTemplate()
            }
            val agentConfigService = mockk<AgentConfigService>().also {
                every { it.findById(agentId) } returns makeAgentConfig()
            }
            val userService = mockk<UserService>().also { svc ->
                normalUserIds.forEach { uid ->
                    every { svc.findById(uid) } returns User(
                        metadata = EntityMetadata(id = uid),
                        externalId = "$uid@example.com",
                        firstname = "User",
                        lastname = uid.toString().take(6),
                    )
                }
                // Simulate missing user for the failing UserRun — resolveContext will throw.
                every { svc.findById(failingUserId) } returns null
            }
            val runtimeMap = mutableMapOf<UUID, CaseRuntime>()
            val caseService = mockk<CaseService>(relaxed = true).also { svc ->
                every { svc.create(any()) } answers {
                    val id = UUID.randomUUID()
                    val flow = MutableStateFlow(CaseStatus.IDLE)
                    val rt = mockk<CaseRuntime>(relaxed = true).also { every { it.statusFlow } returns flow }
                    runtimeMap[id] = rt
                    Case(metadata = EntityMetadata(id = id), namespaceId = namespaceId)
                }
                every { svc.findActiveRuntime(any()) } answers { runtimeMap[firstArg<UUID>()] }
            }

            val testDispatcher = UnconfinedTestDispatcher()
            val exec = ScheduledPromptExecutor(
                scheduledPromptRepository = makeSpRepo(sp),
                runRepository = runRepo,
                userRunRepository = userRunRepo,
                promptService = promptService,
                agentConfigService = agentConfigService,
                caseService = caseService,
                permissionService = mockk(relaxed = true),
                userService = userService,
                properties = SchedulerProperties(batchSize = 10, leaseMinutes = 30L, emptyPollDelayMs = 10L),
                clock = clock,
                dispatcher = testDispatcher,
            )

            runTest(testDispatcher) {
                exec.start()
                // Wait until all 6 UserRuns reach a terminal state (DONE or FAILED).
                withTimeout(5_000L) {
                    while (userRunRepo.all().count { it.status == UserRunStatus.DONE || it.status == UserRunStatus.FAILED } < allUserIds.size) {
                        kotlinx.coroutines.delay(10L)
                    }
                }
                exec.stop()
            }

            // The 5 normal UserRuns must be DONE.
            val doneRuns = userRunRepo.all().filter { it.status == UserRunStatus.DONE }
            doneRuns.size shouldBe normalUserIds.size
            // The failing UserRun must be FAILED.
            val failedRun = userRunRepo.all().first { it.userId == failingUserId }
            failedRun.status shouldBe UserRunStatus.FAILED
        }

        "Phase B: agent config not found marks UserRun FAILED" {
            val sp = makeScheduledPrompt()
            val run = makeRun(sp).copy(status = RunStatus.RUNNING)
            val runRepo = InMemoryScheduledPromptRunRepository().also { it.insert(run) }
            val userRunRepo = makeUserRunRepo(setOf(userId1)).also {
                it.materialize(run.id, agentId, namespaceId)
            }
            val userRun = userRunRepo.claimBatch(java.time.Duration.ofMinutes(30), 10).first()

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
            ).processUserRun(userRun)

            val updated = userRunRepo.all().first { it.id == userRun.id }
            updated.status shouldBe UserRunStatus.FAILED
            updated.error shouldBe "AgentConfig $agentId not found"
        }
    }
}
