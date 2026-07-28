package io.whozoss.agentos.scheduledPrompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.scheduledPrompt.PlanningDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.RecurrenceDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.ScheduledPromptDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class ScheduledPromptControllerUnitSpec : StringSpec({

    val service = mockk<ScheduledPromptService>(relaxed = true)
    val namespaceService = mockk<NamespaceService>(relaxed = true)
    val userService = mockk<UserService>(relaxed = true)
    val permissionService = mockk<PermissionService>(relaxed = true)
    val controller = ScheduledPromptController(
        service, namespaceService, userService, permissionService,
    )

    val namespaceId: UUID = UUID.randomUUID()
    val agentConfigId: UUID = UUID.randomUUID()
    val promptId: UUID = UUID.randomUUID()
    val userId: UUID = UUID.randomUUID()
    val promptContent = "Hello, world!"
    val today: LocalDate = LocalDate.of(2026, 1, 1)

    fun adminUser() = User(metadata = EntityMetadata(id = userId), externalId = "u", isAdmin = true)
    fun regularUser() = User(metadata = EntityMetadata(id = userId), externalId = "u", isAdmin = false)

    fun recurrenceDto(
        every: Int = 1,
        unit: SchedulerUnit = SchedulerUnit.DAY,
        days: List<DayOfWeek> = emptyList(),
        timeUtc: LocalTime = LocalTime.of(8, 0),
    ) = RecurrenceDto(every = every, unit = unit, days = days, timeUtc = timeUtc)

    fun planningDto(
        startDate: LocalDate = today,
        endType: SchedulerEndType = SchedulerEndType.NEVER,
        endDate: LocalDate? = null,
        occurrenceCount: Int? = null,
    ) = PlanningDto(startDate = startDate, endType = endType, endDate = endDate, occurrenceCount = occurrenceCount)

    val fixedNextRun: Instant = Instant.parse("2026-01-01T08:00:00Z")

    fun sp(
        id: UUID = UUID.randomUUID(),
        name: String = "my-prompt",
        nsId: UUID? = namespaceId,
        uid: UUID? = null,
        enabled: Boolean = true,
        recurrence: Recurrence = Recurrence(every = 1, unit = SchedulerUnit.DAY, timeUtc = LocalTime.of(8, 0)),
        planning: Planning = Planning(startDate = today),
        nextRunAt: Instant = fixedNextRun,
        lastRunAt: Instant? = null,
    ) = ScheduledPrompt(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        userId = uid,
        agentConfigId = agentConfigId,
        promptTemplateId = promptId,
        name = name,
        recurrence = recurrence,
        planning = planning,
        enabled = enabled,
        nextRunAt = nextRunAt,
        lastRunAt = lastRunAt,
    )

    fun dto(
        id: UUID? = null,
        name: String = "my-prompt",
        nsId: UUID? = namespaceId,
        uid: UUID? = null,
        enabled: Boolean = true,
        recurrence: RecurrenceDto = recurrenceDto(),
        planning: PlanningDto = planningDto(),
        content: String = promptContent,
    ) = ScheduledPromptDto(
        id = id,
        namespaceId = nsId,
        userId = uid,
        agentConfigId = agentConfigId,
        promptContent = content,
        name = name,
        recurrence = recurrence,
        planning = planning,
        enabled = enabled,
    )

    beforeTest {
        clearAllMocks()
        every { userService.getCurrentUser() } returns adminUser()
        every { namespaceService.findById(any()) } returns Namespace(
            metadata = EntityMetadata(id = namespaceId),
            name = "test-namespace",
        )
        every { permissionService.hasPermission(any(), any(), any(), any()) } returns true
        // Default: service content helpers return the promptContent
        every { service.findByIdWithContent(any(), any()) } answers {
            val id = firstArg<UUID>()
            val withRemoved = secondArg<Boolean>()
            val found = service.findById(id, withRemoved) ?: return@answers null
            Pair(found, promptContent)
        }
        every { service.withContent(any()) } answers {
            firstArg<List<ScheduledPrompt>>().map { Pair(it, promptContent) }
        }
        // Default: createWithPrompt and updateWithPrompt delegate to service
        every { service.createWithPrompt(any(), any()) } answers {
            val entity = firstArg<ScheduledPrompt>()
            val content = secondArg<String>()
            Pair(entity.copy(promptTemplateId = promptId), content)
        }
        every { service.updateWithPrompt(any(), any()) } answers {
            val entity = firstArg<ScheduledPrompt>()
            val content = secondArg<String>()
            Pair(entity, content)
        }
    }

    // -------------------------------------------------------------------------
    // getById
    // -------------------------------------------------------------------------

    "getById returns the DTO when entity exists" {
        val id = UUID.randomUUID()
        val entity = sp(id = id)
        every { service.findById(id, withRemoved = true) } returns entity
        every { service.findByIdWithContent(id, withRemoved = true) } returns Pair(entity, promptContent)
        controller.getById(id).id shouldBe id
    }

    "getById throws 404 when entity does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id, withRemoved = true) } returns null
        every { service.findByIdWithContent(id, withRemoved = true) } returns null
        shouldThrow<ResourceNotFoundException> { controller.getById(id) }
    }

    "getById maps promptContent from service" {
        val id = UUID.randomUUID()
        val entity = sp(id = id)
        every { service.findById(id, withRemoved = true) } returns entity
        every { service.findByIdWithContent(id, withRemoved = true) } returns Pair(entity, "Custom")
        controller.getById(id).promptContent shouldBe "Custom"
    }

    // -------------------------------------------------------------------------
    // create — scope dispatch
    // -------------------------------------------------------------------------

    "create with platform scope (null, null) succeeds for admin" {
        every { service.createWithPrompt(any(), any()) } answers {
            val entity = firstArg<ScheduledPrompt>()
            Pair(entity.copy(namespaceId = null, promptTemplateId = promptId), secondArg())
        }
        controller.create(dto(nsId = null, uid = null)).namespaceId shouldBe null
    }

    "create with namespace scope delegates to service" {
        val result = controller.create(dto(name = "my-prompt"))
        result.name shouldBe "my-prompt"
        result.agentConfigId shouldBe agentConfigId
        result.promptContent shouldBe promptContent
    }

    "create with userId different from current user throws exception" {
        every { userService.getCurrentUser() } returns regularUser()
        shouldThrow<Exception> { controller.create(dto(uid = UUID.randomUUID())) }
    }

    // -------------------------------------------------------------------------
    // create — recurrence and planning round-trips
    // -------------------------------------------------------------------------

    "create round-trips recurrence fields" {
        val time = LocalTime.of(14, 30)
        val r = recurrenceDto(every = 2, unit = SchedulerUnit.WEEK, days = listOf(DayOfWeek.MONDAY), timeUtc = time)
        every { service.createWithPrompt(any(), any()) } answers {
            val entity = firstArg<ScheduledPrompt>()
            Pair(entity.copy(promptTemplateId = promptId), secondArg())
        }
        val result = controller.create(dto(recurrence = r))
        result.recurrence.every shouldBe 2
        result.recurrence.unit shouldBe SchedulerUnit.WEEK
        result.recurrence.days shouldBe listOf(DayOfWeek.MONDAY)
        result.recurrence.timeUtc shouldBe time
    }

    "create round-trips planning ON_DATE" {
        val endDate = today.plusMonths(1)
        val p = planningDto(endType = SchedulerEndType.ON_DATE, endDate = endDate)
        every { service.createWithPrompt(any(), any()) } answers {
            val entity = firstArg<ScheduledPrompt>()
            Pair(entity.copy(promptTemplateId = promptId), secondArg())
        }
        val result = controller.create(dto(planning = p))
        result.planning.endType shouldBe SchedulerEndType.ON_DATE
        result.planning.endDate shouldBe endDate
    }

    "create round-trips planning OCCURRENCES" {
        val p = planningDto(endType = SchedulerEndType.OCCURRENCES, occurrenceCount = 5)
        every { service.createWithPrompt(any(), any()) } answers {
            val entity = firstArg<ScheduledPrompt>()
            Pair(entity.copy(promptTemplateId = promptId), secondArg())
        }
        val result = controller.create(dto(planning = p))
        result.planning.endType shouldBe SchedulerEndType.OCCURRENCES
        result.planning.occurrenceCount shouldBe 5
    }

    // -------------------------------------------------------------------------
    // create — planning cross-field validation (delegated to service)
    // -------------------------------------------------------------------------

    "create propagates BadRequestException from service for ON_DATE without endDate" {
        every { service.createWithPrompt(any(), any()) } throws BadRequestException("endDate is required when endType is ON_DATE")
        shouldThrow<BadRequestException> {
            controller.create(dto(planning = planningDto(endType = SchedulerEndType.ON_DATE, endDate = null)))
        }
    }

    "create propagates BadRequestException from service for OCCURRENCES without count" {
        every { service.createWithPrompt(any(), any()) } throws BadRequestException("occurrenceCount is required when endType is OCCURRENCES")
        shouldThrow<BadRequestException> {
            controller.create(dto(planning = planningDto(endType = SchedulerEndType.OCCURRENCES, occurrenceCount = null)))
        }
    }

    // -------------------------------------------------------------------------
    // create — prompt lifecycle (delegated to service)
    // -------------------------------------------------------------------------

    "create delegates to service.createWithPrompt with promptContent" {
        controller.create(dto(content = "Run the daily report"))
        verify { service.createWithPrompt(any(), "Run the daily report") }
    }

    // -------------------------------------------------------------------------
    // update
    // -------------------------------------------------------------------------

    "update applies mutable changes" {
        val id = UUID.randomUUID()
        val existing = sp(id = id, name = "old")
        every { service.findById(id) } returns existing
        every { service.updateWithPrompt(any(), any()) } answers {
            val entity = firstArg<ScheduledPrompt>()
            Pair(entity, secondArg())
        }
        val result = controller.update(id, dto(id = id, name = "new", enabled = false))
        result.name shouldBe "new"
        result.enabled shouldBe false
    }

    "update throws 404 when entity does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null
        shouldThrow<ResourceNotFoundException> { controller.update(id, dto()) }
    }

    "update preserves immutable fields (namespaceId, agentConfigId)" {
        val id = UUID.randomUUID()
        val existing = sp(id = id, nsId = namespaceId)
        every { service.findById(id) } returns existing
        every { service.updateWithPrompt(any(), any()) } answers {
            val entity = firstArg<ScheduledPrompt>()
            Pair(entity, secondArg())
        }
        val result = controller.update(id, dto(id = id, nsId = UUID.randomUUID()))
        result.namespaceId shouldBe namespaceId
        result.agentConfigId shouldBe agentConfigId
    }

    // -------------------------------------------------------------------------
    // update — planning validation (propagated from service)
    // -------------------------------------------------------------------------

    "update propagates BadRequestException from service for ON_DATE without endDate" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns sp(id = id)
        every { service.updateWithPrompt(any(), any()) } throws BadRequestException("endDate is required when endType is ON_DATE")
        shouldThrow<BadRequestException> {
            controller.update(id, dto(planning = planningDto(endType = SchedulerEndType.ON_DATE, endDate = null)))
        }
    }

    "update propagates BadRequestException from service for OCCURRENCES without count" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns sp(id = id)
        every { service.updateWithPrompt(any(), any()) } throws BadRequestException("occurrenceCount is required when endType is OCCURRENCES")
        shouldThrow<BadRequestException> {
            controller.update(id, dto(planning = planningDto(endType = SchedulerEndType.OCCURRENCES, occurrenceCount = null)))
        }
    }

    // -------------------------------------------------------------------------
    // update — prompt lifecycle (delegated to service)
    // -------------------------------------------------------------------------

    "update delegates to service.updateWithPrompt with promptContent" {
        val id = UUID.randomUUID()
        val existing = sp(id = id, name = "my-prompt")
        every { service.findById(id) } returns existing
        every { service.updateWithPrompt(any(), any()) } answers { Pair(firstArg(), secondArg()) }
        controller.update(id, dto(id = id, content = "Updated prompt content"))
        verify { service.updateWithPrompt(any(), "Updated prompt content") }
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    "delete calls service.deleteWithPrompt when entity exists" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns sp(id = id)
        every { service.deleteWithPrompt(id) } returns true
        controller.delete(id)
        verify { service.deleteWithPrompt(id) }
    }

    "delete throws 404 when entity does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null
        shouldThrow<ResourceNotFoundException> { controller.delete(id) }
    }

    // -------------------------------------------------------------------------
    // toggle
    // -------------------------------------------------------------------------

    "toggle flips enabled from true to false" {
        val id = UUID.randomUUID()
        val existing = sp(id = id, enabled = true)
        val toggled = existing.copy(enabled = false)
        every { service.findById(id) } returns existing
        every { service.toggle(id) } returns toggled
        every { service.findByIdWithContent(toggled.id) } returns Pair(toggled, promptContent)
        controller.toggle(id).enabled shouldBe false
    }

    "toggle flips enabled from false to true" {
        val id = UUID.randomUUID()
        val existing = sp(id = id, enabled = false)
        val toggled = existing.copy(enabled = true)
        every { service.findById(id) } returns existing
        every { service.toggle(id) } returns toggled
        every { service.findByIdWithContent(toggled.id) } returns Pair(toggled, promptContent)
        controller.toggle(id).enabled shouldBe true
    }

    "toggle throws 404 when entity does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null
        shouldThrow<ResourceNotFoundException> { controller.toggle(id) }
    }

    // -------------------------------------------------------------------------
    // toDto mapping
    // -------------------------------------------------------------------------

    "toDto maps all recurrence and planning fields" {
        val id = UUID.randomUUID()
        val time = LocalTime.of(9, 0)
        val r = Recurrence(every = 3, unit = SchedulerUnit.MONTH, days = listOf(DayOfWeek.FRIDAY), timeUtc = time)
        val p = Planning(startDate = today, endType = SchedulerEndType.NEVER)
        val entity = sp(id = id, recurrence = r, planning = p)
        every { service.findById(id, withRemoved = true) } returns entity
        every { service.findByIdWithContent(id, withRemoved = true) } returns Pair(entity, promptContent)
        val result = controller.getById(id)
        result.recurrence.every shouldBe 3
        result.recurrence.unit shouldBe SchedulerUnit.MONTH
        result.recurrence.days shouldBe listOf(DayOfWeek.FRIDAY)
        result.recurrence.timeUtc shouldBe time
        result.planning.endType shouldBe SchedulerEndType.NEVER
    }

    "toDto maps namespaceId and userId" {
        val id = UUID.randomUUID()
        val uid = UUID.randomUUID()
        val entity = sp(id = id, nsId = namespaceId, uid = uid)
        every { service.findById(id, withRemoved = true) } returns entity
        every { service.findByIdWithContent(id, withRemoved = true) } returns Pair(entity, promptContent)
        val result = controller.getById(id)
        result.namespaceId shouldBe namespaceId
        result.userId shouldBe uid
    }

    "toDto maps agentConfigId and promptContent" {
        val id = UUID.randomUUID()
        val entity = sp(id = id)
        every { service.findById(id, withRemoved = true) } returns entity
        every { service.findByIdWithContent(id, withRemoved = true) } returns Pair(entity, promptContent)
        val result = controller.getById(id)
        result.agentConfigId shouldBe agentConfigId
        result.promptContent shouldBe promptContent
    }

    "toDto maps nextRunAt" {
        val id = UUID.randomUUID()
        val entity = sp(id = id, nextRunAt = fixedNextRun)
        every { service.findById(id, withRemoved = true) } returns entity
        every { service.findByIdWithContent(id, withRemoved = true) } returns Pair(entity, promptContent)
        controller.getById(id).nextRunAt shouldBe fixedNextRun
    }

    "toDto maps lastRunAt when set" {
        val id = UUID.randomUUID()
        val lastRun = Instant.parse("2025-12-31T08:00:00Z")
        val entity = sp(id = id, lastRunAt = lastRun)
        every { service.findById(id, withRemoved = true) } returns entity
        every { service.findByIdWithContent(id, withRemoved = true) } returns Pair(entity, promptContent)
        controller.getById(id).lastRunAt shouldBe lastRun
    }

    "toDto maps lastRunAt as null when not yet run" {
        val id = UUID.randomUUID()
        val entity = sp(id = id, lastRunAt = null)
        every { service.findById(id, withRemoved = true) } returns entity
        every { service.findByIdWithContent(id, withRemoved = true) } returns Pair(entity, promptContent)
        controller.getById(id).lastRunAt shouldBe null
    }

    // -------------------------------------------------------------------------
    // Authorization — namespace ADMIN (non-super-admin) can update/toggle/delete
    //
    // Note: @PreAuthorize is not evaluated in unit tests (no Spring Security context).
    // These cases verify that the controller's own authorization logic (scope dispatch,
    // permissionService.hasPermission calls) works correctly for non-admin users.
    // The transitive permission path (Namespace.ADMIN → ScheduledPrompt.WRITE) is
    // exercised by the integration test suite.
    // -------------------------------------------------------------------------

    "update succeeds for non-admin user — controller delegates to service" {
        every { userService.getCurrentUser() } returns regularUser()
        every { permissionService.hasPermission(any(), any(), any(), any()) } returns true
        val id = UUID.randomUUID()
        val existing = sp(id = id, name = "old")
        every { service.findById(id) } returns existing
        every { service.updateWithPrompt(any(), any()) } answers { Pair(firstArg(), secondArg()) }
        val result = controller.update(id, dto(id = id, name = "new"))
        result.name shouldBe "new"
    }

    "toggle succeeds for non-admin user — controller delegates to service" {
        every { userService.getCurrentUser() } returns regularUser()
        every { permissionService.hasPermission(any(), any(), any(), any()) } returns true
        val id = UUID.randomUUID()
        val existing = sp(id = id, enabled = true)
        val toggled = existing.copy(enabled = false)
        every { service.findById(id) } returns existing
        every { service.toggle(id) } returns toggled
        every { service.findByIdWithContent(toggled.id) } returns Pair(toggled, promptContent)
        controller.toggle(id).enabled shouldBe false
    }

    "delete succeeds for non-admin user — controller delegates to service" {
        every { userService.getCurrentUser() } returns regularUser()
        every { permissionService.hasPermission(any(), any(), any(), any()) } returns true
        val id = UUID.randomUUID()
        every { service.findById(id) } returns sp(id = id)
        every { service.deleteWithPrompt(id) } returns true
        controller.delete(id)
        verify { service.deleteWithPrompt(id) }
    }
})
