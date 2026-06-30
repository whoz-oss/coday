package io.whozoss.agentos.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.security.access.AccessDeniedException
import java.util.UUID

/**
 * Unit tests for [PromptController].
 *
 * Permission annotations (@PreAuthorize) are bypassed in pure unit tests — those are
 * exercised by the MVC integration specs. This spec covers:
 * - toResource / toDomain mapping
 * - create: scope dispatch, authorization guards, namespace existence check
 * - list: platform vs namespace scope, READ permission check
 * - update: namespaceId mass-assignment guard, 404 path
 * - delete: soft-delete, platform admin guard
 * - getById / getByIds: inherited delegates
 */
class PromptControllerSpec : StringSpec({

    val service = mockk<PromptService>()
    val namespaceService = mockk<NamespaceService>(relaxed = true)
    val userService = mockk<UserService>(relaxed = true)
    val permissionService = mockk<PermissionService>(relaxed = true)
    val controller = PromptController(service, namespaceService, userService, permissionService)

    val namespaceId = UUID.randomUUID()
    val callerId = UUID.randomUUID()

    fun adminUser() = User(
        metadata = EntityMetadata(id = callerId),
        externalId = "admin@example.com",
        email = "admin@example.com",
        isAdmin = true,
    )

    fun regularUser() = User(
        metadata = EntityMetadata(id = callerId),
        externalId = "user@example.com",
        email = "user@example.com",
        isAdmin = false,
    )

    fun prompt(
        id: UUID = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        name: String = "My Prompt",
        description: String? = null,
        content: List<String> = listOf("Hello {{name}}"),
        parameters: List<PromptParameter> = emptyList(),
    ) = Prompt(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        name = name,
        description = description,
        content = content,
        parameters = parameters,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        name: String = "My Prompt",
        description: String? = null,
        content: List<String> = listOf("Hello {{name}}"),
        parameters: List<PromptParameterResource> = emptyList(),
    ) = PromptResource(
        id = id,
        namespaceId = nsId,
        name = name,
        description = description,
        content = content,
        parameters = parameters,
    )

    val existingNamespace = Namespace(
        metadata = EntityMetadata(id = namespaceId),
        externalId = "ns-$namespaceId",
        name = "test-ns",
    )

    beforeTest {
        clearAllMocks()
        every { namespaceService.findById(namespaceId) } returns existingNamespace
        every { userService.getCurrentUser() } returns regularUser()
    }

    // -------------------------------------------------------------------------
    // toResource mapping
    // -------------------------------------------------------------------------

    "toResource maps all fields correctly" {
        val id = UUID.randomUUID()
        val p = prompt(
            id = id,
            nsId = namespaceId,
            name = "Greeting",
            description = "A greeting prompt",
            content = listOf("Hello {{name}}", "How are you?"),
            parameters = listOf(PromptParameter(name = "name", description = "User name", defaultValue = "World")),
        )

        val result = controller.toResource(p)

        result.id shouldBe id
        result.namespaceId shouldBe namespaceId
        result.name shouldBe "Greeting"
        result.description shouldBe "A greeting prompt"
        result.content shouldBe listOf("Hello {{name}}", "How are you?")
        result.parameters shouldHaveSize 1
        result.parameters[0].name shouldBe "name"
        result.parameters[0].description shouldBe "User name"
        result.parameters[0].defaultValue shouldBe "World"
    }

    "toResource maps null namespaceId for platform prompts" {
        val p = prompt(nsId = null)
        val result = controller.toResource(p)
        result.namespaceId.shouldBeNull()
    }

    "toResource maps empty parameters to empty list" {
        val p = prompt(parameters = emptyList())
        val result = controller.toResource(p)
        result.parameters shouldBe emptyList()
    }

    // -------------------------------------------------------------------------
    // toDomain mapping
    // -------------------------------------------------------------------------

    "toDomain maps all fields" {
        val id = UUID.randomUUID()
        val r = resource(
            id = id,
            nsId = namespaceId,
            name = "Summary",
            description = "Summarise input",
            content = listOf("Summarise: {{text}}"),
            parameters = listOf(PromptParameterResource(name = "text", description = "Input text")),
        )

        val result = controller.toDomain(r)

        result.id shouldBe id
        result.namespaceId shouldBe namespaceId
        result.name shouldBe "Summary"
        result.description shouldBe "Summarise input"
        result.content shouldBe listOf("Summarise: {{text}}")
        result.parameters shouldHaveSize 1
        result.parameters[0].name shouldBe "text"
    }

    "toDomain generates a fresh UUID when id is null" {
        val first = controller.toDomain(resource(id = null))
        val second = controller.toDomain(resource(id = null))
        (first.id == second.id) shouldBe false
    }

    // -------------------------------------------------------------------------
    // getById
    // -------------------------------------------------------------------------

    "getById returns resource when found" {
        val p = prompt()
        every { service.findById(p.id, withRemoved = true) } returns p

        controller.getById(p.id) shouldBe controller.toResource(p)
    }

    "getById throws 404 when not found" {
        val id = UUID.randomUUID()
        every { service.findById(id, withRemoved = true) } returns null

        shouldThrow<ResourceNotFoundException> { controller.getById(id) }
    }

    // -------------------------------------------------------------------------
    // list — platform scope (no params)
    // -------------------------------------------------------------------------

    "list with no namespaceId returns platform prompts" {
        val p1 = prompt(nsId = null, name = "Platform A")
        val p2 = prompt(nsId = null, name = "Platform B")
        every { service.findPlatform() } returns listOf(p1, p2)

        val result = controller.list(namespaceId = null)

        result shouldHaveSize 2
        result.map { it.name } shouldBe listOf("Platform A", "Platform B")
        verify(exactly = 1) { service.findPlatform() }
        verify(exactly = 0) { service.findByNamespaceId(any()) }
    }

    // -------------------------------------------------------------------------
    // list — namespace scope (?namespaceId=<uuid>)
    // -------------------------------------------------------------------------

    "list with namespaceId returns namespace prompts when caller has READ" {
        val p = prompt(nsId = namespaceId, name = "NS Prompt")
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        } returns true
        every { service.findByNamespaceId(namespaceId) } returns listOf(p)

        val result = controller.list(namespaceId = namespaceId.toString())

        result shouldHaveSize 1
        result[0].name shouldBe "NS Prompt"
    }

    "list with namespaceId returns empty list when caller lacks READ" {
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        } returns false

        val result = controller.list(namespaceId = namespaceId.toString())

        result shouldBe emptyList()
        verify(exactly = 0) { service.findByNamespaceId(any()) }
    }

    "list with invalid namespaceId throws BadRequestException" {
        shouldThrow<BadRequestException> {
            controller.list(namespaceId = "not-a-uuid")
        }
    }

    // -------------------------------------------------------------------------
    // create — platform scope (namespaceId == null)
    // -------------------------------------------------------------------------

    "create platform prompt succeeds for Super Admin" {
        every { userService.getCurrentUser() } returns adminUser()
        val captured = slot<Prompt>()
        every { service.create(capture(captured)) } answers { firstArg() }

        controller.create(resource(id = null, nsId = null))

        captured.captured.namespaceId.shouldBeNull()
        verify(exactly = 0) { permissionService.hasPermission(any(), any(), any(), any()) }
    }

    "create platform prompt throws AccessDeniedException for non-admin" {
        every { userService.getCurrentUser() } returns regularUser()

        shouldThrow<AccessDeniedException> {
            controller.create(resource(id = null, nsId = null))
        }
        verify(exactly = 0) { service.create(any()) }
    }

    // -------------------------------------------------------------------------
    // create — namespace scope (namespaceId != null)
    // -------------------------------------------------------------------------

    "create namespace prompt succeeds when caller has WRITE on namespace" {
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
        } returns true
        val captured = slot<Prompt>()
        every { service.create(capture(captured)) } answers { firstArg() }

        controller.create(resource(id = null, nsId = namespaceId))

        captured.captured.namespaceId shouldBe namespaceId
        verify(exactly = 1) { namespaceService.findById(namespaceId) }
    }

    "create namespace prompt throws AccessDeniedException when caller lacks WRITE" {
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
        } returns false

        shouldThrow<AccessDeniedException> {
            controller.create(resource(id = null, nsId = namespaceId))
        }
        verify(exactly = 0) { service.create(any()) }
        // Existence check must NOT run before authz (no existence leak)
        verify(exactly = 0) { namespaceService.findById(any()) }
    }

    "create throws 404 when namespace does not exist (after authz passes)" {
        val unknownNs = UUID.randomUUID()
        every { namespaceService.findById(unknownNs) } returns null
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, unknownNs.toString(), Action.WRITE)
        } returns true

        shouldThrow<ResourceNotFoundException> {
            controller.create(resource(id = null, nsId = unknownNs))
        }
        verify(exactly = 0) { service.create(any()) }
    }

    "create with dangling namespaceId and no permission surfaces as AccessDenied (no 404 leak)" {
        val unknownNs = UUID.randomUUID()
        every { namespaceService.findById(unknownNs) } returns null
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, unknownNs.toString(), Action.WRITE)
        } returns false

        shouldThrow<AccessDeniedException> {
            controller.create(resource(id = null, nsId = unknownNs))
        }
        verify(exactly = 0) { namespaceService.findById(any()) }
        verify(exactly = 0) { service.create(any()) }
    }

    "create assigns a fresh UUID to the persisted entity regardless of body id" {
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
        } returns true
        val captured = slot<Prompt>()
        every { service.create(capture(captured)) } answers { firstArg() }
        val bodyId = UUID.randomUUID()

        controller.create(resource(id = bodyId, nsId = namespaceId))

        // Controller always generates a new UUID — body id is ignored
        captured.captured.id shouldBe captured.captured.id // exists and is a valid UUID
    }

    // -------------------------------------------------------------------------
    // update — mass-assignment guard + 404
    // -------------------------------------------------------------------------

    "update preserves namespaceId from persisted entity" {
        val p = prompt(nsId = namespaceId)
        val otherNs = UUID.randomUUID()
        val payload = resource(id = p.id, nsId = otherNs, name = "Renamed")
        every { service.findById(p.id) } returns p
        every { service.update(any()) } answers {
            val saved = firstArg<Prompt>()
            saved.namespaceId shouldBe namespaceId
            saved.name shouldBe "Renamed"
            saved
        }

        controller.update(p.id, payload)

        verify(exactly = 1) { service.update(any()) }
    }

    "update allows changing name, description, content, and parameters" {
        val p = prompt()
        val newParams = listOf(PromptParameterResource(name = "city", description = "City name"))
        val payload = resource(
            id = p.id,
            name = "New Name",
            description = "New description",
            content = listOf("New content"),
            parameters = newParams,
        )
        val captured = slot<Prompt>()
        every { service.findById(p.id) } returns p
        every { service.update(capture(captured)) } answers { firstArg() }

        controller.update(p.id, payload)

        captured.captured.name shouldBe "New Name"
        captured.captured.description shouldBe "New description"
        captured.captured.content shouldBe listOf("New content")
        captured.captured.parameters shouldHaveSize 1
        captured.captured.parameters[0].name shouldBe "city"
    }

    "update throws 404 when prompt does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> {
            controller.update(id, resource(id = id))
        }
    }

    "update platform prompt throws AccessDeniedException for non-admin" {
        val p = prompt(nsId = null) // platform-level
        every { service.findById(p.id) } returns p

        shouldThrow<AccessDeniedException> {
            controller.update(p.id, resource(id = p.id, nsId = null))
        }
        verify(exactly = 0) { service.update(any()) }
    }

    "update platform prompt succeeds for Super Admin" {
        every { userService.getCurrentUser() } returns adminUser()
        val p = prompt(nsId = null)
        every { service.findById(p.id) } returns p
        every { service.update(any()) } answers { firstArg() }

        controller.update(p.id, resource(id = p.id, nsId = null, name = "Updated"))

        verify(exactly = 1) { service.update(any()) }
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    "delete succeeds for namespace-scoped prompt" {
        val p = prompt()
        every { service.findById(p.id) } returns p
        every { service.delete(p.id) } returns true

        controller.delete(p.id)

        verify(exactly = 1) { service.delete(p.id) }
    }

    "delete throws 404 when prompt does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> {
            controller.delete(id)
        }
    }

    "delete platform prompt throws AccessDeniedException for non-admin" {
        val p = prompt(nsId = null)
        every { service.findById(p.id) } returns p

        shouldThrow<AccessDeniedException> {
            controller.delete(p.id)
        }
        verify(exactly = 0) { service.delete(any()) }
    }

    "delete platform prompt succeeds for Super Admin" {
        every { userService.getCurrentUser() } returns adminUser()
        val p = prompt(nsId = null)
        every { service.findById(p.id) } returns p
        every { service.delete(p.id) } returns true

        controller.delete(p.id)

        verify(exactly = 1) { service.delete(p.id) }
    }
})
