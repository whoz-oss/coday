package io.whozoss.agentos.scheduledPrompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.prompt.Prompt
import io.whozoss.agentos.prompt.PromptService
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
    val agentConfigService = mockk<AgentConfigService>(relaxed = true)
    val promptService = mockk<PromptService>(relaxed = true)
    val namespaceService = mockk<NamespaceService>(relaxed = true)
    val userService = mockk<UserService>(relaxed = true)
    val permissionService = mockk<PermissionService>(relaxed = true)
    val controller = ScheduledPromptController(
        service, agentConfigService, promptService, namespaceService, userService, permissionService,
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

    fun defaultPrompt() = Prompt(
        metadata = EntityMetadata(id = promptId),
        namespaceId = namespaceId,
        agentConfigId = null,
        name = "my-prompt-agent",
        content = listOf(promptContent),
    )

    fun defaultAgent() = AgentConfig(
        metadata = EntityMetadata(id = agentConfigId, version = 0L),
        namespaceId = namespaceId,
        name = "agent",
    )

    beforeTest {
        clearAllMocks()
        every { userService.getCurrentUser() } returns adminUser()
        every { namespaceService.findById(any()) } returns Namespace(
            metadata = EntityMetadata(id = namespaceId),
            name = "test-namespace",
        )
        every { permissionService.hasPermission(any(), any(), any(), any()) } returns true
        every { agentConfigService.findById(agentConfigId) } returns defaultAgent()
        every { promptService.findById(promptId) } returns defaultPrompt()
        every { promptService.create(any()) } returns defaultPrompt()
        every { promptService.update(any()) } returns defaultPrompt()
        every { promptService.delete(any()) } returns true
    }

    // -------------------------------------------------------------------------
    // getById
    // -------------------------------------------------------------------------

    "getById returns the DTO when entity exists" {
        val id = UUID.randomUUID()
        every { service.findById(id, withRemoved = true) } returns sp(id = id)
        controller.getById(id).id shouldBe id
    }

    "getById throws 404 when entity does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id, withRemoved = true) } returns null
        shouldThrow<ResourceNotFoundException> { controller.getById(id) }
    }

    "getById maps promptContent from linked prompt" {
        val id = UUID.randomUUID()
        every { service.findById(id, withRemoved = true) } returns sp(id = id)
        every { promptService.findById(promptId) } returns defaultPrompt().copy(content = listOf("Custom"))
        controller.getById(id).promptContent shouldBe "Custom"
    }

    // -------------------------------------------------------------------------
    // create — scope dispatch
    // -------------------------------------------------------------------------

    "create with platform scope (null, null) succeeds for admin" {
        every { service.create(any()) } returns sp(nsId = null)
        controller.create(dto(nsId = null, uid = null)).namespaceId shouldBe null
    }

    "create with namespace scope delegates to service" {
        every { service.create(any()) } returns sp()
        val result = controller.create(dto())
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
        every { service.create(any()) } returns sp(recurrence = Recurrence(every = 2, unit = SchedulerUnit.WEEK, days = listOf(DayOfWeek.MONDAY), timeUtc = time))
        val result = controller.create(dto(recurrence = r))
        result.recurrence.every shouldBe 2
        result.recurrence.unit shouldBe SchedulerUnit.WEEK
        result.recurrence.days shouldBe listOf(DayOfWeek.MONDAY)
        result.recurrence.timeUtc shouldBe time
    }

    "create round-trips planning ON_DATE" {
        val endDate = today.plusMonths(1)
        val p = planningDto(endType = SchedulerEndType.ON_DATE, endDate = endDate)
        every { service.create(any()) } returns sp(planning = Planning(startDate = today, endType = SchedulerEndType.ON_DATE, endDate = endDate))
        val result = controller.create(dto(planning = p))
        result.planning.endType shouldBe SchedulerEndType.ON_DATE
        result.planning.endDate shouldBe endDate
    }

    "create round-trips planning OCCURRENCES" {
        val p = planningDto(endType = SchedulerEndType.OCCURRENCES, occurrenceCount = 5)
        every { service.create(any()) } returns sp(planning = Planning(startDate = today, endType = SchedulerEndType.OCCURRENCES, occurrenceCount = 5))
        val result = controller.create(dto(planning = p))
        result.planning.endType shouldBe SchedulerEndType.OCCURRENCES
        result.planning.occurrenceCount shouldBe 5
    }

    // -------------------------------------------------------------------------
    // create — planning cross-field validation
    // -------------------------------------------------------------------------

    "create throws BadRequestException when endType is ON_DATE and endDate is null" {
        shouldThrow<BadRequestException> {
            controller.create(dto(planning = planningDto(endType = SchedulerEndType.ON_DATE, endDate = null)))
        }
    }

    "create throws BadRequestException when endDate is not after startDate" {
        shouldThrow<BadRequestException> {
            controller.create(dto(planning = planningDto(endType = SchedulerEndType.ON_DATE, startDate = today, endDate = today)))
        }
    }

    "create throws BadRequestException when endType is OCCURRENCES and occurrenceCount is null" {
        shouldThrow<BadRequestException> {
            controller.create(dto(planning = planningDto(endType = SchedulerEndType.OCCURRENCES, occurrenceCount = null)))
        }
    }

    "create throws BadRequestException when occurrenceCount is 0" {
        shouldThrow<BadRequestException> {
            controller.create(dto(planning = planningDto(endType = SchedulerEndType.OCCURRENCES, occurrenceCount = 0)))
        }
    }

    // -------------------------------------------------------------------------
    // create — prompt lifecycle
    // -------------------------------------------------------------------------

    "create creates a generic prompt with auto-generated name" {
        every { service.create(any()) } returns sp()
        controller.create(dto(name = "my-prompt"))
        verify {
            promptService.create(match { it.name == "my-prompt-agent" && it.agentConfigId == null && it.content == listOf(promptContent) })
        }
    }

    "create passes promptContent to the new prompt" {
        every { service.create(any()) } returns sp()
        controller.create(dto(content = "Run the daily report"))
        verify { promptService.create(match { it.content == listOf("Run the daily report") }) }
    }

    "create links ScheduledPrompt to the newly created prompt id" {
        val newPromptId = UUID.randomUUID()
        every { promptService.create(any()) } returns defaultPrompt().copy(metadata = EntityMetadata(id = newPromptId))
        every { service.create(any()) } returns sp()
        controller.create(dto())
        verify { service.create(match { it.promptTemplateId == newPromptId }) }
    }

    "create throws 404 when agentConfig not found" {
        every { agentConfigService.findById(agentConfigId) } returns null
        shouldThrow<ResourceNotFoundException> { controller.create(dto()) }
    }

    // -------------------------------------------------------------------------
    // update
    // -------------------------------------------------------------------------

    "update applies mutable changes" {
        val id = UUID.randomUUID()
        val existing = sp(id = id, name = "old")
        every { service.findById(id) } returns existing
        every { service.update(any()) } returns existing.copy(name = "new", enabled = false)
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
        every { service.update(any()) } returns existing
        val result = controller.update(id, dto(id = id, nsId = UUID.randomUUID()))
        result.namespaceId shouldBe namespaceId
        result.agentConfigId shouldBe agentConfigId
    }

    // -------------------------------------------------------------------------
    // update — planning validation
    // -------------------------------------------------------------------------

    "update throws BadRequestException when endType is ON_DATE and endDate is null" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns sp(id = id)
        shouldThrow<BadRequestException> {
            controller.update(id, dto(planning = planningDto(endType = SchedulerEndType.ON_DATE, endDate = null)))
        }
    }

    "update throws BadRequestException when endType is OCCURRENCES and occurrenceCount is null" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns sp(id = id)
        shouldThrow<BadRequestException> {
            controller.update(id, dto(planning = planningDto(endType = SchedulerEndType.OCCURRENCES, occurrenceCount = null)))
        }
    }

    // -------------------------------------------------------------------------
    // update — prompt lifecycle
    // -------------------------------------------------------------------------

    "update updates the linked prompt content" {
        val id = UUID.randomUUID()
        val existing = sp(id = id, name = "my-prompt")
        every { service.findById(id) } returns existing
        every { service.update(any()) } returns existing
        controller.update(id, dto(id = id, content = "Updated prompt content"))
        verify { promptService.update(match { it.content == listOf("Updated prompt content") }) }
    }

    "update renames the prompt when entity is renamed" {
        val id = UUID.randomUUID()
        val existing = sp(id = id, name = "old-name")
        every { service.findById(id) } returns existing
        every { service.update(any()) } returns existing.copy(name = "new-name")
        controller.update(id, dto(id = id, name = "new-name"))
        verify { promptService.update(match { it.name == "new-name-agent" }) }
    }

    "update throws 404 when linked prompt not found" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns sp(id = id)
        every { promptService.findById(promptId) } returns null
        shouldThrow<ResourceNotFoundException> { controller.update(id, dto()) }
    }

    "update throws 404 when agentConfig not found" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns sp(id = id)
        every { agentConfigService.findById(agentConfigId) } returns null
        shouldThrow<ResourceNotFoundException> { controller.update(id, dto()) }
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    "delete calls service.delete when entity exists" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns sp(id = id)
        every { service.delete(id) } returns true
        controller.delete(id)
    }

    "delete throws 404 when entity does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null
        shouldThrow<ResourceNotFoundException> { controller.delete(id) }
    }

    "delete also deletes the linked prompt" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns sp(id = id)
        every { service.delete(id) } returns true
        controller.delete(id)
        // promptId here is the local test variable (UUID), used as the promptTemplateId in sp()
        verify { promptService.delete(promptId) }
    }

    // -------------------------------------------------------------------------
    // toggle
    // -------------------------------------------------------------------------

    "toggle flips enabled from true to false" {
        val id = UUID.randomUUID()
        val existing = sp(id = id, enabled = true)
        every { service.findById(id) } returns existing
        every { service.toggle(id) } returns existing.copy(enabled = false)
        controller.toggle(id).enabled shouldBe false
    }

    "toggle flips enabled from false to true" {
        val id = UUID.randomUUID()
        val existing = sp(id = id, enabled = false)
        every { service.findById(id) } returns existing
        every { service.toggle(id) } returns existing.copy(enabled = true)
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
        every { service.findById(id, withRemoved = true) } returns sp(id = id, recurrence = r, planning = p)
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
        every { service.findById(id, withRemoved = true) } returns sp(id = id, nsId = namespaceId, uid = uid)
        val result = controller.getById(id)
        result.namespaceId shouldBe namespaceId
        result.userId shouldBe uid
    }

    "toDto maps agentConfigId and promptContent" {
        val id = UUID.randomUUID()
        every { service.findById(id, withRemoved = true) } returns sp(id = id)
        val result = controller.getById(id)
        result.agentConfigId shouldBe agentConfigId
        result.promptContent shouldBe promptContent
    }

    "toDto maps nextRunAt" {
        val id = UUID.randomUUID()
        every { service.findById(id, withRemoved = true) } returns sp(id = id, nextRunAt = fixedNextRun)
        controller.getById(id).nextRunAt shouldBe fixedNextRun
    }

    "toDto maps lastRunAt when set" {
        val id = UUID.randomUUID()
        val lastRun = Instant.parse("2025-12-31T08:00:00Z")
        every { service.findById(id, withRemoved = true) } returns sp(id = id, lastRunAt = lastRun)
        controller.getById(id).lastRunAt shouldBe lastRun
    }

    "toDto maps lastRunAt as null when not yet run" {
        val id = UUID.randomUUID()
        every { service.findById(id, withRemoved = true) } returns sp(id = id, lastRunAt = null)
        controller.getById(id).lastRunAt shouldBe null
    }
})
