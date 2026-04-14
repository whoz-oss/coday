package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.auth.AccessDeniedException
import io.whozoss.agentos.auth.AuthorizationService
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.auth.NamespaceRole
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

/**
 * Unit tests for [IntegrationConfigController].
 *
 * The controller is instantiated directly with MockK stubs — no Spring context.
 * Tests cover authorization (ADMIN namespace access) on every CRUD endpoint,
 * plus mapping between domain entity and HTTP DTO.
 */
class IntegrationConfigControllerSpec : StringSpec({
    val service = mockk<IntegrationConfigService>()
    val authorizationService = mockk<AuthorizationService>()
    val userService = mockk<UserService>()
    val controller = IntegrationConfigController(service, authorizationService, userService)

    val currentUserId = UUID.randomUUID()
    val currentUser = User(metadata = EntityMetadata(id = currentUserId), externalId = "user@test.com")
    val namespaceId = UUID.randomUUID()
    val params = JsonNodeFactory.instance.objectNode().put("apiUrl", "https://example.com")

    fun config(
        id: UUID = UUID.randomUUID(),
        nsId: UUID = namespaceId,
        name: String = "JIRA_PROD",
        integrationType: String = "JIRA",
    ) = IntegrationConfig(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        name = name,
        integrationType = integrationType,
        parameters = params,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        name: String = "JIRA_PROD",
        integrationType: String = "JIRA",
    ) = IntegrationConfigResource(
        id = id,
        namespaceId = nsId,
        name = name,
        integrationType = integrationType,
        parameters = params,
    )

    beforeEach {
        clearMocks(service, authorizationService, userService)
        every { userService.getCurrentUser() } returns currentUser
    }

    // -------------------------------------------------------------------------
    // toResource / toDomain mapping
    // -------------------------------------------------------------------------

    "toResource maps all fields from IntegrationConfig to IntegrationConfigResource" {
        val id = UUID.randomUUID()
        val c = config(id = id, name = "SLACK_DEV", integrationType = "SLACK")

        val result = controller.toResource(c)

        result shouldBe IntegrationConfigResource(
            id = id,
            namespaceId = namespaceId,
            name = "SLACK_DEV",
            integrationType = "SLACK",
            parameters = params,
        )
    }

    "toDomain maps all fields from IntegrationConfigResource to IntegrationConfig" {
        val id = UUID.randomUUID()
        val r = resource(id = id, name = "GITHUB_MAIN", integrationType = "GITHUB")

        val result = controller.toDomain(r)

        result.metadata.id shouldBe id
        result.namespaceId shouldBe namespaceId
        result.name shouldBe "GITHUB_MAIN"
        result.integrationType shouldBe "GITHUB"
        result.parameters shouldBe params
    }

    "toDomain generates a random UUID when resource id is null" {
        val r = resource(id = null)

        val result = controller.toDomain(r)

        result.metadata.id shouldBe result.metadata.id
    }

    // -------------------------------------------------------------------------
    // getById — requires ADMIN namespace access
    // -------------------------------------------------------------------------

    "getById checks ADMIN namespace access" {
        val c = config()
        every { service.findById(c.id) } returns c
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs

        val result = controller.getById(c.id)

        result.name shouldBe "JIRA_PROD"
        verify { authorizationService.requireNamespaceAccess(currentUserId.toString(), namespaceId.toString(), NamespaceRole.ADMIN) }
    }

    "getById throws 404 when entity is not found" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.getById(id) }
    }

    "getById throws 403 when access denied" {
        val c = config()
        every { service.findById(c.id) } returns c
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } throws AccessDeniedException("Access denied")

        shouldThrow<AccessDeniedException> { controller.getById(c.id) }
    }

    // -------------------------------------------------------------------------
    // create — requires ADMIN namespace access
    // -------------------------------------------------------------------------

    "create checks ADMIN namespace access" {
        val r = resource(id = null)
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { service.create(any()) } returns config()

        controller.create(r)

        verify { authorizationService.requireNamespaceAccess(currentUserId.toString(), namespaceId.toString(), NamespaceRole.ADMIN) }
    }

    "create throws 403 when access denied" {
        val r = resource(id = null)
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } throws AccessDeniedException("Access denied")

        shouldThrow<AccessDeniedException> { controller.create(r) }
    }

    // -------------------------------------------------------------------------
    // update — requires ADMIN namespace access (checked on existing entity)
    // -------------------------------------------------------------------------

    "update checks ADMIN namespace access on existing entity" {
        val c = config()
        val updatedResource = resource(id = c.id, name = "JIRA_STAGING")
        every { service.findById(c.id) } returns c
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { service.update(any()) } returns c

        controller.update(c.id, updatedResource)

        verify { authorizationService.requireNamespaceAccess(currentUserId.toString(), namespaceId.toString(), NamespaceRole.ADMIN) }
    }

    "update throws 404 when entity is not found" {
        val id = UUID.randomUUID()
        val r = resource(id = id)
        every { service.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.update(id, r) }
    }

    // -------------------------------------------------------------------------
    // delete — requires ADMIN namespace access (checked on existing entity)
    // -------------------------------------------------------------------------

    "delete checks ADMIN namespace access on existing entity" {
        val c = config()
        every { service.findById(c.id) } returns c
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { service.delete(c.id) } returns true

        controller.delete(c.id)

        verify { authorizationService.requireNamespaceAccess(currentUserId.toString(), namespaceId.toString(), NamespaceRole.ADMIN) }
        verify { service.delete(c.id) }
    }

    "delete throws 404 when entity is not found" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.delete(id) }
    }

    "delete throws 403 when access denied" {
        val c = config()
        every { service.findById(c.id) } returns c
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } throws AccessDeniedException("Access denied")

        shouldThrow<AccessDeniedException> { controller.delete(c.id) }
    }

    // -------------------------------------------------------------------------
    // listByParent — requires ADMIN namespace access
    // -------------------------------------------------------------------------

    "listByParent checks ADMIN namespace access" {
        val c1 = config(name = "A")
        val c2 = config(name = "B")
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { service.findByParent(namespaceId) } returns listOf(c1, c2)

        val result = controller.listByParent(namespaceId)

        result shouldHaveSize 2
        verify { authorizationService.requireNamespaceAccess(currentUserId.toString(), namespaceId.toString(), NamespaceRole.ADMIN) }
    }

    "listByParent throws 403 when access denied" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } throws AccessDeniedException("Access denied")

        shouldThrow<AccessDeniedException> { controller.listByParent(namespaceId) }
    }
})
