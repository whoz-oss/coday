package io.whozoss.agentos.aiModelConfig

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.aiModel.AiModelController
import io.whozoss.agentos.aiModel.AiModelResource
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [io.whozoss.agentos.aiModel.AiModelController] (Story 4.4).
 *
 * Covers:
 * - `checkCreatePermission` — namespace ADMIN required via parent provider lookup
 * - Missing / user-scoped parent provider refused with 403
 * - `listByParent` / `listByNamespaceId` short-circuit on namespace READ
 * - `update` keeps server-owned-field preservation + adds WRITE permission check
 * - Inherited secured endpoints (getById 404-on-deny, delete 403-on-deny)
 */
class AiModelControllerSpec : StringSpec({

    val service = mockk<AiModelService>()
    val aiProviderService = mockk<AiProviderService>()
    val userService = mockk<UserService>()
    val permissionService = mockk<PermissionService>()
    val controller = AiModelController(service, aiProviderService, userService, permissionService)

    val callerId = UUID.randomUUID()
    val caller = User(
        metadata = EntityMetadata(id = callerId),
        externalId = "member@example.com",
        email = "member@example.com",
        isAdmin = false,
    )
    val namespaceId = UUID.randomUUID()
    val aiProviderId = UUID.randomUUID()

    fun model(
        id: UUID = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        uId: UUID? = null,
        apiName: String = "claude-haiku-4-5",
        alias: String? = "SMALL",
        priority: Int = 0,
    ) = AiModel(
        metadata = EntityMetadata(id = id),
        aiProviderId = aiProviderId,
        namespaceId = nsId,
        userId = uId,
        apiModelName = apiName,
        alias = alias,
        priority = priority,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        apiName: String = "claude-haiku-4-5",
        alias: String? = "SMALL",
        priority: Int = 0,
    ) = AiModelResource(
        id = id,
        aiProviderId = aiProviderId,
        namespaceId = namespaceId,
        apiModelName = apiName,
        alias = alias,
        priority = priority,
    )

    fun provider(nsId: UUID? = namespaceId, uId: UUID? = null) = AiProvider(
        metadata = EntityMetadata(id = aiProviderId),
        namespaceId = nsId,
        userId = uId,
        name = "anthropic",
        apiType = AiApiType.Anthropic,
    )

    beforeTest { clearAllMocks() }

    // -------------------------------------------------------------------------
    // getEntityType + mapping
    // -------------------------------------------------------------------------

    "getEntityType returns \"AiModel\"" {
        controller.getEntityType() shouldBe "AiModel"
    }

    "toResource maps all fields correctly" {
        val id = UUID.randomUUID()
        val m = model(id = id, apiName = "claude-opus-4-6", alias = "BIG", priority = 5)
        val r = controller.toResource(m)
        r.id shouldBe id
        r.aiProviderId shouldBe aiProviderId
        r.namespaceId shouldBe namespaceId
        r.apiModelName shouldBe "claude-opus-4-6"
        r.alias shouldBe "BIG"
        r.priority shouldBe 5
    }

    // -------------------------------------------------------------------------
    // create — namespace ADMIN required via parent provider lookup (AC1, AC4)
    // -------------------------------------------------------------------------

    "create succeeds when caller has WRITE (ADMIN) on the parent provider's namespace" {
        val r = resource(id = null)
        val saved = model()
        every { userService.getCurrentUser() } returns caller
        every { aiProviderService.findById(aiProviderId) } returns provider()
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns true
        every { service.create(any()) } returns saved

        val result = controller.create(r)

        result.id shouldBe saved.metadata.id
        verify(exactly = 1) { service.create(any()) }
    }

    "create throws 403 when caller lacks WRITE on the parent provider's namespace" {
        val r = resource(id = null)
        every { userService.getCurrentUser() } returns caller
        every { aiProviderService.findById(aiProviderId) } returns provider()
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns false

        val ex = shouldThrow<ResponseStatusException> { controller.create(r) }
        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        verify(exactly = 0) { service.create(any()) }
    }

    "create throws 403 when aiProviderId refers to a non-existent provider" {
        val r = resource(id = null)
        every { userService.getCurrentUser() } returns caller
        every { aiProviderService.findById(aiProviderId) } returns null

        val ex = shouldThrow<ResponseStatusException> { controller.create(r) }
        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        verify(exactly = 0) { service.create(any()) }
    }

    "create throws 403 when the parent provider is user-scoped (namespaceId null) — legacy path #809" {
        val r = resource(id = null)
        every { userService.getCurrentUser() } returns caller
        every { aiProviderService.findById(aiProviderId) } returns provider(nsId = null, uId = UUID.randomUUID())

        val ex = shouldThrow<ResponseStatusException> { controller.create(r) }
        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        (ex.reason ?: "") shouldBe "namespace-scoped AiProvider required (user-scoped deprecated, see #809)"
        verify(exactly = 0) { service.create(any()) }
    }

    // -------------------------------------------------------------------------
    // listByParent — short-circuit on namespace READ via provider lookup (AC5)
    // -------------------------------------------------------------------------

    "listByParent returns all models when caller has READ on the provider's namespace (no N+1)" {
        val m1 = model(apiName = "claude-haiku-4-5")
        val m2 = model(apiName = "claude-opus-4-6")
        every { userService.getCurrentUser() } returns caller
        every { aiProviderService.findById(aiProviderId) } returns provider()
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns true
        every { service.findByParent(aiProviderId) } returns listOf(m1, m2)

        val result = controller.listByParent(aiProviderId)

        result.map { it.id } shouldBe listOf(m1.metadata.id, m2.metadata.id)
        verify(exactly = 0) {
            permissionService.hasPermission(any(), "AiModel", any(), any())
        }
    }

    "listByParent returns empty list when caller has no READ on the namespace" {
        every { userService.getCurrentUser() } returns caller
        every { aiProviderService.findById(aiProviderId) } returns provider()
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns false

        controller.listByParent(aiProviderId) shouldBe emptyList()
        verify(exactly = 0) { service.findByParent(any()) }
    }

    "listByParent returns empty list when the provider does not exist" {
        every { userService.getCurrentUser() } returns caller
        every { aiProviderService.findById(aiProviderId) } returns null

        controller.listByParent(aiProviderId) shouldBe emptyList()
        verify(exactly = 0) { service.findByParent(any()) }
    }

    "listByParent returns empty list when the provider is user-scoped" {
        every { userService.getCurrentUser() } returns caller
        every { aiProviderService.findById(aiProviderId) } returns provider(nsId = null, uId = UUID.randomUUID())

        controller.listByParent(aiProviderId) shouldBe emptyList()
        verify(exactly = 0) { service.findByParent(any()) }
    }

    // -------------------------------------------------------------------------
    // listByNamespaceId — short-circuit on namespace READ (AC5)
    // -------------------------------------------------------------------------

    "listByNamespaceId returns all models when caller has READ on the namespace" {
        val m1 = model(apiName = "claude-haiku-4-5")
        val m2 = model(apiName = "claude-opus-4-6")
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns true
        every { service.findByNamespaceId(namespaceId) } returns listOf(m1, m2)

        val result = controller.listByNamespaceId(namespaceId)

        result.map { it.id } shouldBe listOf(m1.metadata.id, m2.metadata.id)
    }

    "listByNamespaceId returns empty list when caller has no READ on the namespace" {
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns false

        controller.listByNamespaceId(namespaceId) shouldBe emptyList()
        verify(exactly = 0) { service.findByNamespaceId(any()) }
    }

    // -------------------------------------------------------------------------
    // update — WRITE permission + server-owned-field preservation
    // -------------------------------------------------------------------------

    "update preserves server-owned fields (namespaceId, aiProviderId) regardless of client payload" {
        val existing = model()
        // Client sends a resource with a different (or null) namespaceId — server must ignore it
        val clientResource = resource(id = existing.metadata.id).copy(namespaceId = null)
        val updatedDomain = existing.copy(alias = "UPDATED")
        every { service.findById(existing.metadata.id) } returns existing
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "AiModel", existing.metadata.id.toString(), Action.WRITE)
        } returns true
        every { service.update(any()) } answers {
            val saved = firstArg<AiModel>()
            saved.namespaceId shouldBe namespaceId
            saved.aiProviderId shouldBe aiProviderId
            updatedDomain
        }

        controller.update(existing.metadata.id, clientResource)

        verify(exactly = 1) { service.update(any()) }
    }

    "update throws 403 when caller lacks WRITE on the AiModel" {
        val existing = model()
        every { service.findById(existing.metadata.id) } returns existing
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "AiModel", existing.metadata.id.toString(), Action.WRITE)
        } returns false

        val ex = shouldThrow<ResponseStatusException> {
            controller.update(existing.metadata.id, resource(id = existing.metadata.id))
        }
        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        verify(exactly = 0) { service.update(any()) }
    }

    "update throws 404 when the AiModel does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> {
            controller.update(id, resource(id = id))
        }
    }

    // -------------------------------------------------------------------------
    // Inherited secured endpoints — sanity
    // -------------------------------------------------------------------------

    "getById returns 404 when caller lacks READ on the AiModel" {
        val m = model()
        every { service.findById(m.metadata.id) } returns m
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "AiModel", m.metadata.id.toString(), Action.READ)
        } returns false

        shouldThrow<ResourceNotFoundException> { controller.getById(m.metadata.id) }
    }

    "delete throws 403 when caller lacks DELETE on the AiModel" {
        val m = model()
        every { service.findById(m.metadata.id) } returns m
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "AiModel", m.metadata.id.toString(), Action.DELETE)
        } returns false

        val ex = shouldThrow<ResponseStatusException> { controller.delete(m.metadata.id) }
        ex.statusCode shouldBe HttpStatus.FORBIDDEN
    }
})
