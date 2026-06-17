package io.whozoss.agentos.agentConfig

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.agent.ResolvedAgentDefinition
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.api.agentConfig.AgentConfigDto
import io.whozoss.agentos.sdk.api.agentConfig.AgentConfigSearchRequest
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

/**
 * Unit tests for [AgentConfigController].
 *
 * Permission checks are declarative (`@PreAuthorize`) and only fire when the
 * controller is invoked through Spring AOP. In pure unit tests we call the
 * controller directly, bypassing the proxy — so authorization paths are NOT
 * exercised here. Those are covered by [io.whozoss.agentos.security.declarative.MethodSecurityIntegrationSpec].
 *
 * What this spec covers:
 * - Mapping (`AgentConfig.toDto()`, including null optional fields)
 * - CRUD delegates: `getById` (found / not-found), `getByIds`, `listByParent`, `create`, `delete`
 * - `update` mass-assignment guard (server-owned `namespaceId` preserved)
 * - `update` 404-on-missing path
 * - `getDefinition` field mapping including [systemPrompt], user overlay flag, tool summaries, 404
 */
class AgentConfigControllerUnitSpec : StringSpec({

    val service = mockk<AgentConfigService>()
    val agentService = mockk<AgentService>()
    val userService = mockk<UserService>()
    val permissionService = mockk<PermissionService>()
    val controller = AgentConfigController(service, agentService, userService, permissionService)

    val namespaceId = UUID.randomUUID()
    val callerId = UUID.randomUUID()
    val superAdmin = User(
        metadata = EntityMetadata(id = callerId),
        externalId = "root@example.com",
        email = "root@example.com",
        isAdmin = true,
    )
    val regularUser = User(
        metadata = EntityMetadata(id = callerId),
        externalId = "user@example.com",
        email = "user@example.com",
        isAdmin = false,
    )

    fun config(
        id: UUID = UUID.randomUUID(),
        nsId: UUID = namespaceId,
        name: String = "my-agent",
        description: String? = "An agent",
        instructions: String? = "Be helpful.",
        modelName: String? = "BIG",
        externalMetadata: Map<String, Any?>? = null,
    ) = AgentConfig(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        name = name,
        description = description,
        instructions = instructions,
        modelName = modelName,
        externalMetadata = externalMetadata,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        nsId: UUID = namespaceId,
        name: String = "my-agent",
        description: String? = "An agent",
        instructions: String? = "Be helpful.",
        modelName: String? = "BIG",
        externalMetadata: Map<String, Any?>? = null,
    ) = AgentConfigDto(
        id = id,
        namespaceId = nsId,
        name = name,
        description = description,
        instructions = instructions,
        modelName = modelName,
        externalMetadata = externalMetadata,
    )

    fun resolvedDefinition(
        agentConfigId: UUID = UUID.randomUUID(),
        name: String = "my-agent",
        systemPrompt: String? = "## Context: engineering",
        instructions: String? = "Be helpful.",
        resolvedModelApiName: String = "claude-sonnet-4-5",
        resolvedProviderName: String = "anthropic-prod",
        advancedExecution: Boolean = false,
        nsId: UUID = namespaceId,
        userId: UUID? = null,
    ) = ResolvedAgentDefinition(
        agentConfigId = agentConfigId,
        name = name,
        systemPrompt = systemPrompt,
        instructions = instructions,
        resolvedModelApiName = resolvedModelApiName,
        resolvedProviderName = resolvedProviderName,
        resolvedModelId = UUID.randomUUID(),
        resolvedProviderId = UUID.randomUUID(),
        resolvedModel = AiModel(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            aiProviderId = UUID.randomUUID(),
            namespaceId = nsId,
            apiModelName = resolvedModelApiName,
        ),
        resolvedProvider = AiProvider(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = nsId,
            name = resolvedProviderName,
            apiType = AiApiType.Anthropic,
            baseUrl = "https://api.anthropic.com",
            apiKey = "sk-ant-test",
        ),
        tools = emptyList(),
        advancedExecution = advancedExecution,
        namespaceId = nsId,
        userId = userId,
    )

    beforeTest { clearAllMocks() }

    // -------------------------------------------------------------------------
    // toDto mapping (file-level extension AgentConfig.toDto())
    // -------------------------------------------------------------------------

    "toDto maps all fields from AgentConfig to AgentConfigDto" {
        val id = UUID.randomUUID()
        val c = config(
            id = id,
            name = "coder",
            description = "Writes code",
            instructions = "Write clean code.",
            modelName = "claude-3-opus",
        )

        val result = c.toDto()

        result shouldBe AgentConfigDto(
            id = id,
            namespaceId = namespaceId,
            name = "coder",
            description = "Writes code",
            instructions = "Write clean code.",
            modelName = "claude-3-opus",
            createdOn = c.metadata.created,
            updatedOn = c.metadata.modified,
            enabled = false,
        )
    }

    "toDto preserves null optional fields" {
        val c = config(description = null, instructions = null, modelName = null)
        val result = c.toDto()
        result.description shouldBe null
        result.instructions shouldBe null
        result.modelName shouldBe null
    }

    "toDto maps advancedExecution=false to null (omitted in JSON)" {
        config().copy(advancedExecution = false).toDto().advancedExecution shouldBe null
    }

    "toDto maps advancedExecution=true to true" {
        config().copy(advancedExecution = true).toDto().advancedExecution shouldBe true
    }

    "toDto maps externalMetadata when present" {
        val metadata = mapOf("theme" to "TALENT", "starters" to listOf("hello"))
        config(externalMetadata = metadata).toDto().externalMetadata shouldBe metadata
    }

    "toDto preserves null externalMetadata" {
        config(externalMetadata = null).toDto().externalMetadata shouldBe null
    }

    "toDto maps audit fields from EntityMetadata" {
        val created = java.time.Instant.parse("2024-01-01T00:00:00Z")
        val modified = java.time.Instant.parse("2024-06-01T12:00:00Z")
        val c = config().copy(
            metadata = EntityMetadata(
                id = UUID.randomUUID(), created = created, createdBy = "user-abc",
                modified = modified, modifiedBy = "user-xyz",
            ),
        )
        val result = c.toDto()
        result.createdBy shouldBe "user-abc"
        result.createdOn shouldBe created
        result.updatedBy shouldBe "user-xyz"
        result.updatedOn shouldBe modified
    }

    "toDto returns null audit fields when EntityMetadata has none" {
        val c = config()
        val result = c.toDto()
        result.createdBy shouldBe null
        result.updatedBy shouldBe null
    }

    // -------------------------------------------------------------------------
    // getById
    // -------------------------------------------------------------------------

    "getById returns a dto when the entity is found" {
        val c = config()
        every { service.findById(c.id, withRemoved = true) } returns c

        val result = controller.getById(c.id)

        result shouldBe c.toDto()
    }

    "getById throws 404 when entity is not found" {
        val id = UUID.randomUUID()
        every { service.findById(id, withRemoved = true) } returns null

        shouldThrow<ResourceNotFoundException> { controller.getById(id) }
    }

    // -------------------------------------------------------------------------
    // getByIds (inherited)
    // -------------------------------------------------------------------------

    "getByIds returns all matching entities for a super-admin caller (admin bypass)" {
        val c1 = config(name = "agent-a")
        val c2 = config(name = "agent-b")
        every { userService.getCurrentUser() } returns superAdmin
        every { service.findByIds(setOf(c1.id, c2.id), false) } returns listOf(c1, c2)

        val result = controller.getByIds(GetByIdsRequest(ids = listOf(c1.id, c2.id)))

        result shouldBe listOf(c1.toDto(), c2.toDto())
    }

    "getByIds filters via permissionService.filterVisibleIds for a regular caller" {
        val c1 = config(name = "agent-visible")
        val c2 = config(name = "agent-denied")
        every { userService.getCurrentUser() } returns regularUser
        every {
            permissionService.filterVisibleIds(
                callerId.toString(), EntityType.AGENT_CONFIG, listOf(c1.id.toString(), c2.id.toString()), Action.READ,
            )
        } returns setOf(c1.id.toString())
        every { service.findByIds(setOf(c1.id, c2.id), false) } returns listOf(c1, c2)

        val result = controller.getByIds(GetByIdsRequest(ids = listOf(c1.id, c2.id)))

        result shouldBe listOf(c1.toDto())
    }

    "getByIds returns empty list for a regular caller with no visible ids (without hitting service.findByIds)" {
        val c1 = config()
        every { userService.getCurrentUser() } returns regularUser
        every {
            permissionService.filterVisibleIds(any(), any(), any(), any())
        } returns emptySet()
        every { service.findByIds(any(), any()) } returns emptyList()

        controller.getByIds(GetByIdsRequest(ids = listOf(c1.id))) shouldBe emptyList()
    }

    "getByIds short-circuits to empty list on empty input WITHOUT touching userService or permissionService" {
        controller.getByIds(GetByIdsRequest(ids = emptyList())) shouldBe emptyList()
        verify(exactly = 0) { userService.getCurrentUser() }
        verify(exactly = 0) { permissionService.filterVisibleIds(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // listByParent (inherited)
    // -------------------------------------------------------------------------

    "listByParent returns configs for the given namespaceId" {
        val c1 = config(name = "alpha")
        val c2 = config(name = "beta")
        every { service.findByNamespace(namespaceId, withDisabled = true) } returns listOf(c1, c2)

        val result = controller.listByParent(namespaceId)

        result shouldBe listOf(c1.toDto(), c2.toDto())
        verify(exactly = 1) { service.findByNamespace(namespaceId, withDisabled = true) }
    }

    "listByParent with withDisabled=false returns only enabled configs" {
        val c1 = config(name = "published").copy(enabled = true)
        every { service.findByNamespace(namespaceId, withDisabled = false) } returns listOf(c1)

        val result = controller.listByParent(namespaceId, withDisabled = false)

        result shouldBe listOf(c1.toDto())
        verify(exactly = 1) { service.findByNamespace(namespaceId, withDisabled = false) }
    }

    // -------------------------------------------------------------------------
    // create (inherited)
    // -------------------------------------------------------------------------

    "create converts resource to domain, delegates to service, and returns mapped dto" {
        val r = resource(id = null)
        every { service.create(any()) } answers { firstArg<AgentConfig>() }

        val result = controller.create(r)

        result.name shouldBe r.name
        result.namespaceId shouldBe r.namespaceId
        verify(exactly = 1) { service.create(any()) }
    }

    // -------------------------------------------------------------------------
    // update — delegate happy path + mass-assignment guard + 404
    // -------------------------------------------------------------------------

    "update delegates to service when entity exists and returns mapped dto" {
        val c = config()
        val updatedResource = resource(id = c.id, name = "updated-agent")
        every { service.findById(c.id) } returns c
        every { service.update(any()) } answers { firstArg() }

        val result = controller.update(c.id, updatedResource)

        result.name shouldBe "updated-agent"
        verify(exactly = 1) { service.update(any()) }
    }

    "update preserves the persisted namespaceId when client sends a different value" {
        val c = config()
        val otherNs = UUID.randomUUID()
        val payload = resource(id = c.id, nsId = otherNs, name = "renamed")
        every { service.findById(c.id) } returns c
        every { service.update(any()) } answers {
            val saved = firstArg<AgentConfig>()
            saved.namespaceId shouldBe namespaceId
            saved.name shouldBe "renamed"
            saved
        }

        controller.update(c.id, payload)

        verify(exactly = 1) { service.update(any()) }
    }

    "update throws 404 when the AgentConfig does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.update(id, resource(id = id)) }
    }

    "update preserves externalMetadata from the request payload" {
        val metadata = mapOf("theme" to "OPERATIONS", "starters" to emptyList<String>())
        val c = config()
        val payload = resource(id = c.id, externalMetadata = metadata)
        every { service.findById(c.id) } returns c
        every { service.update(any()) } answers {
            val saved = firstArg<AgentConfig>()
            saved.externalMetadata shouldBe metadata
            saved
        }

        controller.update(c.id, payload)

        verify(exactly = 1) { service.update(any()) }
    }

    "update clears externalMetadata when not provided in payload" {
        val existingMetadata = mapOf("theme" to "TALENT")
        val c = config(externalMetadata = existingMetadata)
        val payload = resource(id = c.id, externalMetadata = null)
        every { service.findById(c.id) } returns c
        every { service.update(any()) } answers {
            val saved = firstArg<AgentConfig>()
            saved.externalMetadata shouldBe null
            saved
        }

        controller.update(c.id, payload)

        verify(exactly = 1) { service.update(any()) }
    }

    // -------------------------------------------------------------------------
    // availableAgents
    // -------------------------------------------------------------------------

    "search returns mapped dtos for given namespaceId and userExternalId" {
        val c1 = config(name = "agent-a")
        val c2 = config(name = "agent-b")
        val searchNsId = UUID.randomUUID()
        val request = AgentConfigSearchRequest(namespaceId = searchNsId, userExternalId = "alice@example.com")
        every { service.findAvailableByUserExternalId(searchNsId, "alice@example.com") } returns listOf(c1, c2)

        val result = controller.search(request)

        result shouldBe listOf(c1.toDto(), c2.toDto())
        verify(exactly = 1) { service.findAvailableByUserExternalId(searchNsId, "alice@example.com") }
    }

    "search returns empty list when service returns no agents" {
        val request = AgentConfigSearchRequest(namespaceId = namespaceId, userExternalId = "ghost@example.com")
        every { service.findAvailableByUserExternalId(namespaceId, "ghost@example.com") } returns emptyList()

        controller.search(request) shouldBe emptyList()
    }

    // -------------------------------------------------------------------------
    // delete (inherited)
    // -------------------------------------------------------------------------

    "delete succeeds when entity exists" {
        val id = UUID.randomUUID()
        every { service.delete(id) } returns true

        controller.delete(id)

        verify(exactly = 1) { service.delete(id) }
    }

    "delete throws 404 when service returns false" {
        val id = UUID.randomUUID()
        every { service.delete(id) } returns false

        shouldThrow<ResourceNotFoundException> { controller.delete(id) }
    }

    // -------------------------------------------------------------------------
    // getDefinition
    // -------------------------------------------------------------------------

    "getDefinition maps all fields including systemPrompt from ResolvedAgentDefinition" {
        val configId = UUID.randomUUID()
        val agentConfig = config(id = configId)
        val definition = resolvedDefinition(
            agentConfigId = configId,
            systemPrompt = "## Context: engineering",
            instructions = "Be helpful.",
        )
        every { service.findById(configId) } returns agentConfig
        every { userService.getCurrentUser() } returns superAdmin
        coEvery { agentService.resolveDefinition(configId, namespaceId, null) } returns definition

        val result = controller.getDefinition(configId, withUserOverlay = false)

        result.agentConfigId shouldBe configId
        result.name shouldBe definition.name
        result.systemPrompt shouldBe "## Context: engineering"
        result.instructions shouldBe "Be helpful."
        result.resolvedModelApiName shouldBe definition.resolvedModelApiName
        result.resolvedProviderName shouldBe definition.resolvedProviderName
        result.advancedExecution shouldBe false
        result.namespaceId shouldBe namespaceId
        result.userId shouldBe null
    }

    "getDefinition maps null systemPrompt when no namespace context is available" {
        val configId = UUID.randomUUID()
        val agentConfig = config(id = configId)
        val definition = resolvedDefinition(agentConfigId = configId, systemPrompt = null)
        every { service.findById(configId) } returns agentConfig
        every { userService.getCurrentUser() } returns superAdmin
        coEvery { agentService.resolveDefinition(configId, namespaceId, null) } returns definition

        val result = controller.getDefinition(configId, withUserOverlay = false)

        result.systemPrompt shouldBe null
    }

    "getDefinition resolves with userId when withUserOverlay=true" {
        val configId = UUID.randomUUID()
        val agentConfig = config(id = configId)
        val definition = resolvedDefinition(agentConfigId = configId, userId = callerId)
        every { service.findById(configId) } returns agentConfig
        every { userService.getCurrentUser() } returns superAdmin
        coEvery { agentService.resolveDefinition(configId, namespaceId, callerId) } returns definition

        val result = controller.getDefinition(configId, withUserOverlay = true)

        result.userId shouldBe callerId
        coVerify(exactly = 1) { agentService.resolveDefinition(configId, namespaceId, callerId) }
    }

    "getDefinition maps tool summaries" {
        val configId = UUID.randomUUID()
        val agentConfig = config(id = configId)
        val tool = mockk<StandardTool<*>> {
            every { name } returns "get-issue"
            every { description } returns "Fetches a Jira issue"
            every { inputSchema } returns "{\"type\":\"object\"}"
        }
        val definition = resolvedDefinition(agentConfigId = configId).copy(tools = listOf(tool))
        every { service.findById(configId) } returns agentConfig
        every { userService.getCurrentUser() } returns superAdmin
        coEvery { agentService.resolveDefinition(configId, namespaceId, null) } returns definition

        val result = controller.getDefinition(configId, withUserOverlay = false)

        result.tools.size shouldBe 1
        result.tools[0].name shouldBe "get-issue"
        result.tools[0].description shouldBe "Fetches a Jira issue"
        result.tools[0].inputSchema shouldBe "{\"type\":\"object\"}"
    }

    "getDefinition throws 404 when AgentConfig is not found" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.getDefinition(id, withUserOverlay = false) }
    }
})
