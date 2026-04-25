package io.whozoss.agentos.aiProvider

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
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [AiProviderController] (Story 4.3).
 *
 * Covers:
 * - `checkCreatePermission` — namespace ADMIN required; user-scoped creation
 *   refused with 403 (AC6)
 * - `listByParent` short-circuit on namespace READ (AC4)
 * - Inherited secured endpoints (getById 404-on-deny, delete 403-on-deny)
 * - Mapping (toResource/toDomain) — apiKey passed through as-is
 */
class AiProviderControllerSpec : StringSpec({

    val service = mockk<AiProviderService>()
    val userService = mockk<UserService>()
    val permissionService = mockk<PermissionService>()
    val controller = AiProviderController(service, userService, permissionService)

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
        nsId: UUID? = namespaceId,
        uId: UUID? = null,
        name: String = "anthropic",
        apiKey: String? = null,
    ) = AiProvider(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        userId = uId,
        name = name,
        apiType = AiApiType.Anthropic,
        apiKey = apiKey,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        uId: UUID? = null,
        name: String = "anthropic",
        apiKey: String? = null,
    ) = AiProviderResource(
        id = id,
        namespaceId = nsId,
        userId = uId,
        name = name,
        apiType = AiApiType.Anthropic,
        apiKey = apiKey,
    )

    beforeTest { clearAllMocks() }

    // -------------------------------------------------------------------------
    // getEntityType
    // -------------------------------------------------------------------------

    "getEntityType returns \"AiProvider\"" {
        controller.getEntityType() shouldBe "AiProvider"
    }

    // -------------------------------------------------------------------------
    // Mapping — apiKey is passed through verbatim
    // -------------------------------------------------------------------------

    "toResource passes the apiKey through unchanged" {
        controller.toResource(config(apiKey = "sk-ant-api03-abcdefghijklmnop")).apiKey shouldBe
            "sk-ant-api03-abcdefghijklmnop"
    }

    "toResource maps namespaceId and userId" {
        val uid = UUID.randomUUID()
        val r = controller.toResource(config(nsId = namespaceId, uId = uid))
        r.namespaceId shouldBe namespaceId
        r.userId shouldBe uid
    }

    // -------------------------------------------------------------------------
    // create — namespace ADMIN required (AC1) + user-scoped refused (AC6)
    // -------------------------------------------------------------------------

    "create succeeds when caller has WRITE (ADMIN) on the parent namespace" {
        val r = resource(id = null, apiKey = "sk-ant-api03-abcdefghijklmnop")
        val saved = config(apiKey = "sk-ant-api03-abcdefghijklmnop")
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns true
        every { service.create(any()) } returns saved

        val result = controller.create(r)

        result.id shouldBe saved.metadata.id
        result.apiKey shouldBe "sk-ant-api03-abcdefghijklmnop"
        verify(exactly = 1) { service.create(any()) }
    }

    "create throws 403 when caller lacks WRITE on the parent namespace" {
        val r = resource(id = null)
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.WRITE)
        } returns false

        val ex = shouldThrow<ResponseStatusException> { controller.create(r) }
        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        verify(exactly = 0) { service.create(any()) }
    }

    "create throws 403 when the entity is user-scoped (namespaceId null) — legacy path #809" {
        val r = resource(id = null, nsId = null, uId = UUID.randomUUID())
        every { userService.getCurrentUser() } returns caller

        val ex = shouldThrow<ResponseStatusException> { controller.create(r) }

        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        (ex.reason ?: "") shouldBe "namespace-scoped AiProvider required (user-scoped deprecated, see #809)"
        verify(exactly = 0) { service.create(any()) }
    }

    // -------------------------------------------------------------------------
    // listByParent — READ on namespace short-circuit (AC4)
    // -------------------------------------------------------------------------

    "listByParent returns all providers when caller has READ on namespace (no N+1)" {
        val p1 = config(name = "openai")
        val p2 = config(name = "anthropic")
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns true
        every { service.findByParent(namespaceId) } returns listOf(p1, p2)

        val result = controller.listByParent(namespaceId)

        result.map { it.id } shouldBe listOf(p1.metadata.id, p2.metadata.id)
        verify(exactly = 0) {
            permissionService.hasPermission(any(), "AiProvider", any(), any())
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
    // Inherited secured endpoints — sanity
    // -------------------------------------------------------------------------

    "getById returns 404 when caller lacks READ on the AiProvider" {
        val p = config()
        every { service.findById(p.metadata.id) } returns p
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "AiProvider", p.metadata.id.toString(), Action.READ)
        } returns false

        shouldThrow<ResourceNotFoundException> { controller.getById(p.metadata.id) }
    }

    "delete throws 403 when caller lacks DELETE on the AiProvider" {
        val p = config()
        every { service.findById(p.metadata.id) } returns p
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "AiProvider", p.metadata.id.toString(), Action.DELETE)
        } returns false

        val ex = shouldThrow<ResponseStatusException> { controller.delete(p.metadata.id) }
        ex.statusCode shouldBe HttpStatus.FORBIDDEN
    }

    // -------------------------------------------------------------------------
    // update — server-owned-field preservation (P1: mass-assignment guard)
    // -------------------------------------------------------------------------

    "update preserves the persisted namespaceId and userId when client sends different values" {
        val existing = config(apiKey = "real-key")
        val otherNs = UUID.randomUUID()
        val otherUser = UUID.randomUUID()
        val payload = resource(id = existing.metadata.id, nsId = otherNs, uId = otherUser, name = "renamed")
        every { service.findById(existing.metadata.id) } returns existing
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "AiProvider", existing.metadata.id.toString(), Action.WRITE)
        } returns true
        every { service.update(any()) } answers {
            val saved = firstArg<AiProvider>()
            saved.namespaceId shouldBe namespaceId
            saved.userId shouldBe null
            saved.name shouldBe "renamed"
            saved
        }

        controller.update(existing.metadata.id, payload)

        verify(exactly = 1) { service.update(any()) }
    }

    "update keeps the persisted apiKey when client sends a blank apiKey" {
        val existing = config(apiKey = "real-key")
        val payload = resource(id = existing.metadata.id, apiKey = "")
        every { service.findById(existing.metadata.id) } returns existing
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "AiProvider", existing.metadata.id.toString(), Action.WRITE)
        } returns true
        every { service.update(any()) } answers {
            val saved = firstArg<AiProvider>()
            saved.apiKey shouldBe "real-key"
            saved
        }

        controller.update(existing.metadata.id, payload)
    }

    "update replaces the persisted apiKey when client sends a non-blank apiKey" {
        val existing = config(apiKey = "old-key")
        val payload = resource(id = existing.metadata.id, apiKey = "new-key")
        every { service.findById(existing.metadata.id) } returns existing
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "AiProvider", existing.metadata.id.toString(), Action.WRITE)
        } returns true
        every { service.update(any()) } answers {
            val saved = firstArg<AiProvider>()
            saved.apiKey shouldBe "new-key"
            saved
        }

        controller.update(existing.metadata.id, payload)
    }

    "update throws 403 when caller lacks WRITE on the AiProvider" {
        val existing = config()
        every { service.findById(existing.metadata.id) } returns existing
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "AiProvider", existing.metadata.id.toString(), Action.WRITE)
        } returns false

        val ex = shouldThrow<ResponseStatusException> {
            controller.update(existing.metadata.id, resource(id = existing.metadata.id))
        }
        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        verify(exactly = 0) { service.update(any()) }
    }

    "update throws 404 when the AiProvider does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.update(id, resource(id = id)) }
    }

    // -------------------------------------------------------------------------
    // Legacy secured endpoints (P2)
    // -------------------------------------------------------------------------

    "listByNamespaceId returns providers when caller has READ on the namespace" {
        val p1 = config(name = "alpha")
        val p2 = config(name = "beta")
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns true
        every { service.findByNamespaceId(namespaceId) } returns listOf(p1, p2)

        controller.listByNamespaceId(namespaceId).map { it.id } shouldBe listOf(p1.metadata.id, p2.metadata.id)
    }

    "listByNamespaceId returns empty list when caller has no READ on the namespace" {
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(callerId.toString(), "Namespace", namespaceId.toString(), Action.READ)
        } returns false

        controller.listByNamespaceId(namespaceId) shouldBe emptyList()
        verify(exactly = 0) { service.findByNamespaceId(any()) }
    }

    "listByUserId returns providers when caller is the targeted user (self)" {
        val p1 = config(name = "personal")
        every { userService.getCurrentUser() } returns caller
        every { service.findByUserId(callerId) } returns listOf(p1)

        controller.listByUserId(callerId).map { it.id } shouldBe listOf(p1.metadata.id)
    }

    "listByUserId returns providers when caller is a super-admin" {
        val otherUser = UUID.randomUUID()
        val superAdmin = caller.copy(isAdmin = true)
        val p1 = config(name = "their-personal")
        every { userService.getCurrentUser() } returns superAdmin
        every { service.findByUserId(otherUser) } returns listOf(p1)

        controller.listByUserId(otherUser).map { it.id } shouldBe listOf(p1.metadata.id)
    }

    "listByUserId returns empty list when caller is a different non-admin user" {
        val otherUser = UUID.randomUUID()
        every { userService.getCurrentUser() } returns caller

        controller.listByUserId(otherUser) shouldBe emptyList()
        verify(exactly = 0) { service.findByUserId(any()) }
    }
})
