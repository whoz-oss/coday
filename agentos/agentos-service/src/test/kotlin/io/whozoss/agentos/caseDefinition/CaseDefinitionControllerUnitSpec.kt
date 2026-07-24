package io.whozoss.agentos.caseDefinition

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.prompt.Prompt
import io.whozoss.agentos.prompt.PromptService
import io.whozoss.agentos.sdk.api.caseDefinition.CaseDefinitionDto
import io.whozoss.agentos.sdk.api.caseDefinition.CaseDefinitionScheduleFrequency
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [CaseDefinitionController].
 *
 * Mocks [CaseDefinitionService], [AgentConfigService], [PromptService],
 * [NamespaceService], [UserService] and [PermissionService].
 * Authorization guards (@PreAuthorize) are NOT exercised here (no Spring Security context) —
 * those are covered in [CaseDefinitionControllerIntegrationSpec].
 */
class CaseDefinitionControllerUnitSpec : StringSpec({

    val service = mockk<CaseDefinitionService>(relaxed = true)
    val agentConfigService = mockk<AgentConfigService>(relaxed = true)
    val promptService = mockk<PromptService>(relaxed = true)
    val namespaceService = mockk<NamespaceService>(relaxed = true)
    val userService = mockk<UserService>(relaxed = true)
    val permissionService = mockk<PermissionService>(relaxed = true)
    val controller = CaseDefinitionController(
        service,
        agentConfigService,
        promptService,
        namespaceService,
        userService,
        permissionService,
    )

    val namespaceId: UUID = UUID.randomUUID()
    val agentConfigId: UUID = UUID.randomUUID()
    val promptId: UUID = UUID.randomUUID()
    val userId: UUID = UUID.randomUUID()
    val promptContent = "Hello, world!"

    fun adminUser() = User(
        metadata = EntityMetadata(id = userId),
        externalId = "test-user",
        isAdmin = true,
    )

    fun regularUser() = User(
        metadata = EntityMetadata(id = userId),
        externalId = "test-user",
        isAdmin = false,
    )

    fun def(
        id: UUID = UUID.randomUUID(),
        name: String = "my-def",
        nsId: UUID? = namespaceId,
        uid: UUID? = null,
        enabled: Boolean = true,
        cronExpression: String = "0 8 * * *",
    ) = CaseDefinition(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        userId = uid,
        agentConfigId = agentConfigId,
        promptId = promptId,
        name = name,
        cronExpression = cronExpression,
        enabled = enabled,
    )

    fun dto(
        id: UUID? = null,
        name: String = "my-def",
        nsId: UUID? = namespaceId,
        uid: UUID? = null,
        enabled: Boolean = true,
        frequency: CaseDefinitionScheduleFrequency = CaseDefinitionScheduleFrequency.DAILY,
        timeUtc: String = "08:00",
        dayOfWeek: String? = null,
        content: String = promptContent,
    ) = CaseDefinitionDto(
        id = id,
        namespaceId = nsId,
        userId = uid,
        agentConfigId = agentConfigId,
        promptContent = content,
        name = name,
        frequency = frequency,
        timeUtc = timeUtc,
        dayOfWeek = dayOfWeek,
        enabled = enabled,
    )

    fun defaultPrompt() = Prompt(
        metadata = EntityMetadata(id = promptId),
        namespaceId = namespaceId,
        agentConfigId = null,
        name = "my-def-agent",
        content = listOf(promptContent),
    )

    fun defaultAgent() = AgentConfig(
        metadata = EntityMetadata(id = agentConfigId, version = 0L),
        namespaceId = namespaceId,
        name = "agent",
    )

    beforeTest {
        clearAllMocks()
        // Re-stub after clearAllMocks.
        // Relaxed mocks return a generic Entity subtype for findById, which cannot be cast to
        // Namespace inside the controller. Stubbing explicitly prevents the ClassCastException.
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

    "getById returns the DTO when definition exists" {
        val id = UUID.randomUUID()
        every { service.findById(id, withRemoved = true) } returns def(id = id)
        val result = controller.getById(id)
        result.id shouldBe id
    }

    "getById throws 404 when definition does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id, withRemoved = true) } returns null
        shouldThrow<ResourceNotFoundException> { controller.getById(id) }
    }

    "getById maps promptContent from linked prompt" {
        val id = UUID.randomUUID()
        every { service.findById(id, withRemoved = true) } returns def(id = id)
        every { promptService.findById(promptId) } returns defaultPrompt().copy(content = listOf("Custom content"))
        val result = controller.getById(id)
        result.promptContent shouldBe "Custom content"
    }

    // -------------------------------------------------------------------------
    // create — scope dispatch
    // -------------------------------------------------------------------------

    "create with platform scope (null, null) succeeds for admin" {
        val saved = def(nsId = null)
        every { service.create(any()) } returns saved
        val result = controller.create(dto(nsId = null, uid = null))
        result.namespaceId shouldBe null
    }

    "create with namespace scope delegates to service" {
        val saved = def()
        every { service.create(any()) } returns saved
        val result = controller.create(dto())
        result.name shouldBe saved.name
        result.agentConfigId shouldBe agentConfigId
        result.promptContent shouldBe promptContent
    }

    "create with userId in body different from current user throws 400" {
        every { userService.getCurrentUser() } returns regularUser()
        val otherUser = UUID.randomUUID()
        shouldThrow<Exception> { controller.create(dto(uid = otherUser)) }
    }

    "create WEEKLY without dayOfWeek throws 400" {
        shouldThrow<ResponseStatusException> {
            controller.create(dto(frequency = CaseDefinitionScheduleFrequency.WEEKLY))
        }
    }

    "create WEEKLY with dayOfWeek succeeds" {
        val saved = def(cronExpression = "30 14 * * FRI")
        every { service.create(any()) } returns saved
        val result = controller.create(dto(frequency = CaseDefinitionScheduleFrequency.WEEKLY, timeUtc = "14:30", dayOfWeek = "FRI"))
        result.frequency shouldBe CaseDefinitionScheduleFrequency.WEEKLY
        result.dayOfWeek shouldBe "FRI"
    }

    "create sets namespaceId from body" {
        val saved = def(nsId = namespaceId)
        every { service.create(any()) } returns saved
        val result = controller.create(dto())
        result.namespaceId shouldBe namespaceId
    }

    "create with userId builds user-scoped entity" {
        every { userService.getCurrentUser() } returns regularUser()
        val saved = def(uid = userId)
        every { service.create(any()) } returns saved
        val result = controller.create(dto(uid = userId))
        result.userId shouldBe userId
    }

    // -------------------------------------------------------------------------
    // create — prompt lifecycle
    // -------------------------------------------------------------------------

    "create creates a generic prompt with auto-generated name" {
        val saved = def()
        every { service.create(any()) } returns saved
        controller.create(dto(name = "my-def"))
        verify {
            promptService.create(
                match { prompt ->
                    prompt.name == "my-def-agent" &&
                        prompt.agentConfigId == null &&
                        prompt.content == listOf(promptContent)
                },
            )
        }
    }

    "create passes promptContent to the new prompt" {
        val saved = def()
        every { service.create(any()) } returns saved
        controller.create(dto(content = "Run the daily report"))
        verify {
            promptService.create(
                match { prompt ->
                    prompt.content == listOf("Run the daily report")
                },
            )
        }
    }

    "create links CaseDefinition to the newly created prompt id" {
        val newPromptId = UUID.randomUUID()
        val newPrompt = defaultPrompt().copy(metadata = EntityMetadata(id = newPromptId))
        every { promptService.create(any()) } returns newPrompt
        val saved = def()
        every { service.create(any()) } returns saved
        controller.create(dto())
        verify {
            service.create(
                match { def -> def.promptId == newPromptId },
            )
        }
    }

    "create resolves agentConfig name for prompt name" {
        every { agentConfigService.findById(agentConfigId) } returns defaultAgent().copy(name = "my-agent")
        val saved = def()
        every { service.create(any()) } returns saved
        controller.create(dto(name = "daily-sync"))
        verify {
            promptService.create(
                match { prompt -> prompt.name == "daily-sync-my-agent" },
            )
        }
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
        val existing = def(id = id, name = "old")
        every { service.findById(id) } returns existing
        every { service.update(any()) } returns existing.copy(name = "new", enabled = false)
        val result = controller.update(id, dto(id = id, name = "new", enabled = false))
        result.name shouldBe "new"
        result.enabled shouldBe false
    }

    "update throws 404 when definition does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null
        shouldThrow<ResourceNotFoundException> { controller.update(id, dto()) }
    }

    "update preserves immutable fields (namespaceId, userId, agentConfigId)" {
        val id = UUID.randomUUID()
        val existing = def(id = id, nsId = namespaceId)
        every { service.findById(id) } returns existing
        // update() copies immutable fields from existing, not from the DTO
        every { service.update(any()) } returns existing
        val result = controller.update(id, dto(id = id, nsId = UUID.randomUUID())) // different nsId in body — ignored
        result.namespaceId shouldBe namespaceId
        result.agentConfigId shouldBe agentConfigId
    }

    // -------------------------------------------------------------------------
    // update — prompt lifecycle
    // -------------------------------------------------------------------------

    "update updates the linked prompt content" {
        val id = UUID.randomUUID()
        val existing = def(id = id, name = "my-def")
        every { service.findById(id) } returns existing
        every { service.update(any()) } returns existing
        controller.update(id, dto(id = id, content = "Updated prompt content"))
        verify {
            promptService.update(
                match { prompt -> prompt.content == listOf("Updated prompt content") },
            )
        }
    }

    "update renames the prompt when definition is renamed" {
        val id = UUID.randomUUID()
        val existing = def(id = id, name = "old-name")
        every { service.findById(id) } returns existing
        every { service.update(any()) } returns existing.copy(name = "new-name")
        controller.update(id, dto(id = id, name = "new-name"))
        verify {
            promptService.update(
                match { prompt -> prompt.name == "new-name-agent" },
            )
        }
    }

    "update throws 404 when linked prompt not found" {
        val id = UUID.randomUUID()
        val existing = def(id = id)
        every { service.findById(id) } returns existing
        every { promptService.findById(promptId) } returns null
        shouldThrow<ResourceNotFoundException> { controller.update(id, dto()) }
    }

    "update throws 404 when agentConfig not found" {
        val id = UUID.randomUUID()
        val existing = def(id = id)
        every { service.findById(id) } returns existing
        every { agentConfigService.findById(agentConfigId) } returns null
        shouldThrow<ResourceNotFoundException> { controller.update(id, dto()) }
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    "delete calls service.delete when definition exists" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns def(id = id)
        every { service.delete(id) } returns true
        controller.delete(id)
    }

    "delete throws 404 when definition does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null
        shouldThrow<ResourceNotFoundException> { controller.delete(id) }
    }

    // -------------------------------------------------------------------------
    // delete — prompt lifecycle
    // -------------------------------------------------------------------------

    "delete also deletes the linked prompt" {
        val id = UUID.randomUUID()
        val existing = def(id = id)
        every { service.findById(id) } returns existing
        every { service.delete(id) } returns true
        controller.delete(id)
        verify { promptService.delete(promptId) }
    }

    // -------------------------------------------------------------------------
    // toggle
    // -------------------------------------------------------------------------

    "toggle flips enabled from true to false" {
        val id = UUID.randomUUID()
        val existing = def(id = id, enabled = true)
        every { service.findById(id) } returns existing
        every { service.toggle(id) } returns existing.copy(enabled = false)
        controller.toggle(id).enabled shouldBe false
    }

    "toggle flips enabled from false to true" {
        val id = UUID.randomUUID()
        val existing = def(id = id, enabled = false)
        every { service.findById(id) } returns existing
        every { service.toggle(id) } returns existing.copy(enabled = true)
        controller.toggle(id).enabled shouldBe true
    }

    "toggle throws 404 when definition does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null
        shouldThrow<ResourceNotFoundException> { controller.toggle(id) }
    }

    // -------------------------------------------------------------------------
    // toDto mapping
    // -------------------------------------------------------------------------

    "toDto maps DAILY cron" {
        val id = UUID.randomUUID()
        every { service.findById(id, withRemoved = true) } returns def(id = id, cronExpression = "0 9 * * *")
        val result = controller.getById(id)
        result.frequency shouldBe CaseDefinitionScheduleFrequency.DAILY
        result.timeUtc shouldBe "09:00"
        result.dayOfWeek shouldBe null
    }

    "toDto maps WEEKLY cron" {
        val id = UUID.randomUUID()
        every { service.findById(id, withRemoved = true) } returns def(id = id, cronExpression = "30 14 * * FRI")
        val result = controller.getById(id)
        result.frequency shouldBe CaseDefinitionScheduleFrequency.WEEKLY
        result.timeUtc shouldBe "14:30"
        result.dayOfWeek shouldBe "FRI"
    }

    "toDto maps namespaceId and userId" {
        val id = UUID.randomUUID()
        val uid = UUID.randomUUID()
        every { service.findById(id, withRemoved = true) } returns def(id = id, nsId = namespaceId, uid = uid)
        val result = controller.getById(id)
        result.namespaceId shouldBe namespaceId
        result.userId shouldBe uid
    }

    "toDto maps agentConfigId and promptContent" {
        val id = UUID.randomUUID()
        every { service.findById(id, withRemoved = true) } returns def(id = id)
        val result = controller.getById(id)
        result.agentConfigId shouldBe agentConfigId
        result.promptContent shouldBe promptContent
    }

    // -------------------------------------------------------------------------
    // toCron mapping on create
    // -------------------------------------------------------------------------

    "create DAILY builds correct cron" {
        val saved = def(cronExpression = "0 9 * * *")
        every { service.create(any()) } returns saved
        // The saved entity has cron "0 9 * * *" which maps to DAILY / 09:00
        val result = controller.create(dto(frequency = CaseDefinitionScheduleFrequency.DAILY, timeUtc = "09:00"))
        result.frequency shouldBe CaseDefinitionScheduleFrequency.DAILY
        result.timeUtc shouldBe "09:00"
    }

    "create WEEKLY builds correct cron" {
        val saved = def(cronExpression = "30 14 * * FRI")
        every { service.create(any()) } returns saved
        // The saved entity has cron "30 14 * * FRI" which maps to WEEKLY / 14:30 / FRI
        val result = controller.create(dto(frequency = CaseDefinitionScheduleFrequency.WEEKLY, timeUtc = "14:30", dayOfWeek = "FRI"))
        result.frequency shouldBe CaseDefinitionScheduleFrequency.WEEKLY
        result.dayOfWeek shouldBe "FRI"
    }
})
