package io.whozoss.agentos.agentConfig

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
 * Unit tests for [AgentConfigController] (Story 4.1).
 *
 * Covers:
 * - Mapping (toResource, toDomain)
 * - `checkCreatePermission` gate — WRITE on parent namespace required (FR17/18/19)
 * - `listByParent` short-circuit — READ on namespace enough to see everything (FR21)
 * - Inherited secured endpoints (getById 404-on-deny, update/delete 403-on-deny)
 *
 * The full SecuredEntityController framework is tested in `SecuredEntityControllerSpec`
 * — here we only cover the overrides that are specific to AgentConfig.
 */
class AgentConfigControllerUnitSpec : StringSpec({

    val service = mockk<AgentConfigService>()
    val userService = mockk<UserService>()
    val permissionService = mockk<PermissionService>()
    val controller = AgentConfigController(service, userService, permissionService)

    val callerId = UUID.randomUUID()
    val caller = User(
        metadata = EntityMetadata(id = callerId),
        externalId = "member@example.com",
        email = "member@example.com",
        isAdmin = false,
    )
    val namespaceId = UUID.randomUUID()

    fun config(
        id: UUID = UUID.randomUUID(),
        nsId: UUID = namespaceId,
        name: String = "my-agent",
        description: String? = "An agent",
        instructions: String? = "Be helpful.",
        modelName: String? = "BIG",
    ) = AgentConfig(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        name = name,
        description = description,
        instructions = instructions,
        modelName = modelName,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        nsId: UUID = namespaceId,
        name: String = "my-agent",
    ) = AgentConfigResource(
        id = id,
        namespaceId = nsId,
        name = name,
    )

    beforeTest { clearAllMocks() }

    // -------------------------------------------------------------------------
    // getEntityType (must match Neo4j label)
    // -------------------------------------------------------------------------

    "getEntityType returns \"AgentConfig\"" {
        controller.getEntityType() shouldBe "AgentConfig"
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    "toResource maps all fields" {
        val c = config(name = "coder", description = "Writes code", modelName = "claude-3-opus")
        val r = controller.toResource(c)

        r.id shouldBe c.metadata.id
        r.namespaceId shouldBe namespaceId
        r.name shouldBe "coder"
        r.description shouldBe "Writes code"
        r.modelName shouldBe "claude-3-opus"
    }

    "toDomain maps all fields and generates an id when null" {
        val first = controller.toDomain(resource(id = null))
        val second = controller.toDomain(resource(id = null))

        // Two null-id resources must produce distinct UUIDs — proves a fresh UUID is generated
        (first.metadata.id == second.metadata.id) shouldBe false
    }

    // -------------------------------------------------------------------------
    // create — namespace ADMIN required (Story 4.1 AC1)
    // -------------------------------------------------------------------------

    "create succeeds when caller has WRITE (ADMIN) on the parent namespace" {
        val r = resource(id = null, name = "new-agent")
        val saved = config(name = "new-agent")
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
        val r = resource(id = null, name = "new-agent")
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns false

        val ex = shouldThrow<ResponseStatusException> { controller.create(r) }

        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        verify(exactly = 0) { service.create(any()) }
    }

    // -------------------------------------------------------------------------
    // listByParent — READ on namespace short-circuit (Story 4.1 AC4)
    // -------------------------------------------------------------------------

    "listByParent returns all configs when caller has READ on the parent namespace (no N+1)" {
        val c1 = config(name = "alpha")
        val c2 = config(name = "beta")
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns true
        every { service.findByParent(namespaceId) } returns listOf(c1, c2)

        val result = controller.listByParent(namespaceId)

        result.map { it.id } shouldBe listOf(c1.metadata.id, c2.metadata.id)
        // No per-entity hasPermission call — the namespace-level check is enough
        verify(exactly = 0) {
            permissionService.hasPermission(any(), "AgentConfig", any(), any())
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

    "getById returns 404 when caller lacks READ on the AgentConfig" {
        val c = config()
        every { service.findById(c.id) } returns c
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "AgentConfig", c.id.toString(), Action.READ)
        } returns false

        shouldThrow<ResourceNotFoundException> { controller.getById(c.id) }
    }

    "update throws 403 when caller lacks WRITE on the AgentConfig" {
        val c = config()
        every { service.findById(c.id) } returns c
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "AgentConfig", c.id.toString(), Action.WRITE)
        } returns false

        val ex = shouldThrow<ResponseStatusException> { controller.update(c.id, resource(id = c.id)) }
        ex.statusCode shouldBe HttpStatus.FORBIDDEN
    }

    "update preserves the persisted namespaceId when client sends a different value (mass-assignment guard)" {
        val c = config()
        val otherNs = UUID.randomUUID()
        val payload = resource(id = c.id, nsId = otherNs, name = "renamed")
        every { service.findById(c.id) } returns c
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "AgentConfig", c.id.toString(), Action.WRITE)
        } returns true
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

    "delete throws 403 when caller lacks DELETE on the AgentConfig" {
        val c = config()
        every { service.findById(c.id) } returns c
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "AgentConfig", c.id.toString(), Action.DELETE)
        } returns false

        val ex = shouldThrow<ResponseStatusException> { controller.delete(c.id) }
        ex.statusCode shouldBe HttpStatus.FORBIDDEN
    }

    "delete succeeds for super-admin (hasPermission DELETE returns true via bypass)" {
        val superAdmin = caller.copy(isAdmin = true)
        val c = config()
        every { service.findById(c.id) } returns c
        every { userService.getCurrentUser() } returns superAdmin
        every {
            permissionService.hasPermission(callerId.toString(), "AgentConfig", c.id.toString(), Action.DELETE)
        } returns true
        every { service.delete(c.id) } returns true

        controller.delete(c.id)

        verify(exactly = 1) { service.delete(c.id) }
    }
})
