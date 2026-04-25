package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [IntegrationConfigController] (Story 4.2).
 *
 * Covers:
 * - Mapping (toResource, toDomain)
 * - `checkCreatePermission` gate — WRITE on parent namespace required (FR23/24/25)
 * - `listByParent` short-circuit — READ on namespace enough to see everything (FR27)
 * - Inherited secured endpoints (getById 404-on-deny, update/delete 403-on-deny)
 */
class IntegrationConfigControllerSpec : StringSpec({

    val service = mockk<IntegrationConfigService>()
    val userService = mockk<UserService>()
    val permissionService = mockk<PermissionService>()
    val controller = IntegrationConfigController(service, userService, permissionService)

    val callerId = UUID.randomUUID()
    val caller = User(
        metadata = EntityMetadata(id = callerId),
        externalId = "member@example.com",
        email = "member@example.com",
        isAdmin = false,
    )
    val namespaceId = UUID.randomUUID()
    val params = JsonNodeFactory.instance.objectNode().put("apiUrl", "https://example.com")

    fun config(
        id: UUID = UUID.randomUUID(),
        nsId: UUID = namespaceId,
        name: String = "JIRA_PROD",
        integrationType: String = "JIRA",
        description: String? = null,
    ) = IntegrationConfig(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        name = name,
        integrationType = integrationType,
        description = description,
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
        description = null,
        parameters = params,
    )

    beforeTest { clearAllMocks() }

    // -------------------------------------------------------------------------
    // getEntityType — must match the Neo4j label
    // -------------------------------------------------------------------------

    "getEntityType returns \"IntegrationConfig\"" {
        controller.getEntityType() shouldBe "IntegrationConfig"
    }

    // -------------------------------------------------------------------------
    // Mapping (unchanged by Story 4.2)
    // -------------------------------------------------------------------------

    "toResource maps all fields" {
        val c = config(name = "SLACK_DEV", integrationType = "SLACK", description = "Dev Slack")
        val r = controller.toResource(c)

        r.name shouldBe "SLACK_DEV"
        r.integrationType shouldBe "SLACK"
        r.description shouldBe "Dev Slack"
        r.parameters shouldBe params
    }

    "toDomain maps all fields and generates UUID when id is null" {
        val a = controller.toDomain(resource(id = null))
        val b = controller.toDomain(resource(id = null))

        // Two null-id resources must produce distinct UUIDs
        (a.metadata.id == b.metadata.id) shouldBe false
    }

    // -------------------------------------------------------------------------
    // create — namespace ADMIN required (Story 4.2 AC1)
    // -------------------------------------------------------------------------

    "create succeeds when caller has WRITE (ADMIN) on the parent namespace" {
        val r = resource(id = null, name = "new-integration")
        val saved = config(name = "new-integration")
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns true
        every { service.create(any()) } returns saved

        val result = controller.create(r)

        result.id shouldBe saved.metadata.id
        verify(exactly = 1) { service.create(any()) }
    }

    "create throws 403 when caller lacks WRITE on the parent namespace" {
        val r = resource(id = null, name = "new-integration")
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns false

        val ex = shouldThrow<ResponseStatusException> { controller.create(r) }

        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        verify(exactly = 0) { service.create(any()) }
    }

    // -------------------------------------------------------------------------
    // listByParent — READ on namespace short-circuit (Story 4.2 AC3)
    // -------------------------------------------------------------------------

    "listByParent returns all configs when caller has READ on the parent namespace (no N+1)" {
        val c1 = config(name = "A")
        val c2 = config(name = "B")
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns true
        every { service.findByParent(namespaceId) } returns listOf(c1, c2)

        val result = controller.listByParent(namespaceId)

        result.map { it.id } shouldBe listOf(c1.metadata.id, c2.metadata.id)
        // No per-entity check — the namespace-level READ is enough
        verify(exactly = 0) {
            permissionService.hasPermission(any(), "IntegrationConfig", any(), any())
        }
    }

    "listByParent returns empty list when caller has no READ on the parent namespace" {
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns false

        controller.listByParent(namespaceId) shouldBe emptyList()
        verify(exactly = 0) { service.findByParent(any()) }
    }

    // -------------------------------------------------------------------------
    // Inherited secured endpoints — quick sanity checks
    // -------------------------------------------------------------------------

    "getById returns 404 when caller lacks READ on the IntegrationConfig" {
        val c = config()
        every { service.findById(c.id) } returns c
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "IntegrationConfig", c.id.toString(), Action.READ)
        } returns false

        shouldThrow<ResourceNotFoundException> { controller.getById(c.id) }
    }

    "update throws 403 when caller lacks WRITE on the IntegrationConfig" {
        val c = config()
        every { service.findById(c.id) } returns c
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "IntegrationConfig", c.id.toString(), Action.WRITE)
        } returns false

        val ex = shouldThrow<ResponseStatusException> { controller.update(c.id, resource(id = c.id)) }
        ex.statusCode shouldBe HttpStatus.FORBIDDEN
    }

    "update preserves the persisted namespaceId when client sends a different value (mass-assignment guard)" {
        val c = config()
        val otherNs = UUID.randomUUID()
        val payload = resource(id = c.id, nsId = otherNs, name = "RENAMED")
        every { service.findById(c.id) } returns c
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "IntegrationConfig", c.id.toString(), Action.WRITE)
        } returns true
        every { service.update(any()) } answers {
            val saved = firstArg<IntegrationConfig>()
            saved.namespaceId shouldBe namespaceId
            saved.name shouldBe "RENAMED"
            saved
        }

        controller.update(c.id, payload)

        verify(exactly = 1) { service.update(any()) }
    }

    "update throws 404 when the IntegrationConfig does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.update(id, resource(id = id)) }
    }

    "delete throws 403 when caller lacks DELETE on the IntegrationConfig" {
        val c = config()
        every { service.findById(c.id) } returns c
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "IntegrationConfig", c.id.toString(), Action.DELETE)
        } returns false

        val ex = shouldThrow<ResponseStatusException> { controller.delete(c.id) }
        ex.statusCode shouldBe HttpStatus.FORBIDDEN
    }
})
