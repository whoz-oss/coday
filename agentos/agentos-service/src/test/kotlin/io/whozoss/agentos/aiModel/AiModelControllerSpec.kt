package io.whozoss.agentos.aiModel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for the unified [AiModelController].
 *
 * Guards ([AiModelGuard]) and services ([AiModelService]) are mocked so the tests
 * exercise the controller's own logic : verdict dispatch, SF4 silent-strip, list
 * mode dispatch, update field preservation, getByIds ownership branch, and the
 * listByParent hard-break stub.
 *
 * `create()` reads auth from [SecurityContextHolder] (fixed EntityController signature),
 * so the [withAuth] helper is used to push/pop the security context per test.
 * `list()` accepts `auth: Authentication` as an explicit parameter and is called directly.
 */
class AiModelControllerSpec : StringSpec({

    val aiModelService = mockk<AiModelService>()
    val aiModelGuard = mockk<AiModelGuard>()
    val userService = mockk<UserService>(relaxed = true)
    val permissionService = mockk<PermissionService>(relaxed = true)
    val controller = AiModelController(aiModelService, aiModelGuard, userService, permissionService)

    val aliceId = UUID.randomUUID()
    val namespaceId = UUID.randomUUID()
    val aiProviderId = UUID.randomUUID()

    fun authFor(userId: UUID): Authentication =
        UsernamePasswordAuthenticationToken(userId.toString(), "n/a", emptyList())

    fun <T> withAuth(userId: UUID = aliceId, block: () -> T): T {
        val previous = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication = authFor(userId)
        return try {
            block()
        } finally {
            SecurityContextHolder.getContext().authentication = previous
        }
    }

    fun model(
        id: UUID = UUID.randomUUID(),
        providerId: UUID = aiProviderId,
        nsId: UUID? = namespaceId,
        uId: UUID? = aliceId,
        apiModelName: String = "claude-haiku-4-5",
        alias: String? = "SMALL",
        priority: Int = 0,
        temperature: Double? = 0.7,
        maxTokens: Int? = 1024,
    ) = AiModel(
        metadata = EntityMetadata(id = id),
        aiProviderId = providerId,
        namespaceId = nsId,
        userId = uId,
        apiModelName = apiModelName,
        alias = alias,
        priority = priority,
        temperature = temperature,
        maxTokens = maxTokens,
    )

    fun resource(
        id: UUID? = null,
        providerId: UUID? = aiProviderId,
        nsId: UUID? = null,
        uId: UUID? = null,
        apiModelName: String = "claude-haiku-4-5",
        alias: String? = "SMALL",
        priority: Int = 0,
        temperature: Double? = 0.7,
        maxTokens: Int? = 1024,
    ) = AiModelResource(
        id = id,
        aiProviderId = providerId,
        namespaceId = nsId,
        userId = uId,
        apiModelName = apiModelName,
        alias = alias,
        priority = priority,
        temperature = temperature,
        maxTokens = maxTokens,
    )

    fun aliceUser(admin: Boolean = false) = User(
        metadata = EntityMetadata(id = aliceId),
        externalId = "alice@example.com",
        email = "alice@example.com",
        isAdmin = admin,
    )

    beforeTest {
        clearAllMocks()
        every { userService.getCurrentUser() } returns aliceUser()
    }

    // -------------------------------------------------------------------------
    // 1-4) toResource — mapping
    // -------------------------------------------------------------------------

    "toResource maps all fields correctly" {
        val m = model()
        val r = controller.toResource(m)

        r.id shouldBe m.metadata.id
        r.aiProviderId shouldBe aiProviderId
        r.namespaceId shouldBe namespaceId
        r.userId shouldBe aliceId
        r.apiModelName shouldBe "claude-haiku-4-5"
        r.alias shouldBe "SMALL"
        r.priority shouldBe 0
        r.temperature shouldBe 0.7
        r.maxTokens shouldBe 1024
    }

    "toResource maps null optional fields without error" {
        val m = model(nsId = null, uId = null, alias = null, temperature = null, maxTokens = null)
        val r = controller.toResource(m)

        r.namespaceId shouldBe null
        r.userId shouldBe null
        r.alias shouldBe null
        r.temperature shouldBe null
        r.maxTokens shouldBe null
    }

    "toResource preserves aiProviderId from model" {
        val differentProviderId = UUID.randomUUID()
        val m = model(providerId = differentProviderId)
        val r = controller.toResource(m)

        r.aiProviderId shouldBe differentProviderId
    }

    "toResource preserves priority from model" {
        val m = model(priority = 5)
        val r = controller.toResource(m)

        r.priority shouldBe 5
    }

    // -------------------------------------------------------------------------
    // 5-6) create — Ok verdicts
    // -------------------------------------------------------------------------

    "create with parent NS-shared (owned) returns 201 when verdict is Ok" {
        val m = model(nsId = namespaceId, uId = null)
        every { aiModelGuard.canCreateVerdict(any(), any()) } returns AiModelGuard.CreateVerdict.Ok
        every { aiModelService.create(any()) } returns m

        val result = withAuth { controller.create(resource()) }

        result.apiModelName shouldBe "claude-haiku-4-5"
        verify(exactly = 1) { aiModelService.create(any()) }
    }

    "create with parent owned by me (user-scope) returns 201 when verdict is Ok" {
        val m = model(nsId = null, uId = aliceId)
        every { aiModelGuard.canCreateVerdict(any(), any()) } returns AiModelGuard.CreateVerdict.Ok
        every { aiModelService.create(any()) } returns m

        val result = withAuth { controller.create(resource()) }

        result.userId shouldBe aliceId
        verify(exactly = 1) { aiModelService.create(any()) }
    }

    // -------------------------------------------------------------------------
    // 7-9) create — ParentInvisible -> AccessDeniedException
    // -------------------------------------------------------------------------

    "create with missing parent throws AccessDeniedException (ParentInvisible -> 404)" {
        every { aiModelGuard.canCreateVerdict(any(), any()) } returns AiModelGuard.CreateVerdict.ParentInvisible

        shouldThrow<AccessDeniedException> {
            withAuth { controller.create(resource()) }
        }
        verify(exactly = 0) { aiModelService.create(any()) }
    }

    "create with parent owned by another user throws AccessDeniedException (cross-user -> 404)" {
        every { aiModelGuard.canCreateVerdict(any(), any()) } returns AiModelGuard.CreateVerdict.ParentInvisible

        shouldThrow<AccessDeniedException> {
            withAuth { controller.create(resource()) }
        }
        verify(exactly = 0) { aiModelService.create(any()) }
    }

    "create with NS-shared parent where caller is non-member throws AccessDeniedException (SF1 : 404)" {
        every { aiModelGuard.canCreateVerdict(any(), any()) } returns AiModelGuard.CreateVerdict.ParentInvisible

        shouldThrow<AccessDeniedException> {
            withAuth { controller.create(resource()) }
        }
        verify(exactly = 0) { aiModelService.create(any()) }
    }

    // -------------------------------------------------------------------------
    // 10) create — ParentNotWritable -> 403
    // -------------------------------------------------------------------------

    "create with NS-shared parent without WRITE throws ResponseStatusException 403 (ParentNotWritable)" {
        every { aiModelGuard.canCreateVerdict(any(), any()) } returns AiModelGuard.CreateVerdict.ParentNotWritable

        val ex = shouldThrow<ResponseStatusException> {
            withAuth { controller.create(resource()) }
        }
        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        verify(exactly = 0) { aiModelService.create(any()) }
    }

    // -------------------------------------------------------------------------
    // 11) create — service propagates 409
    // -------------------------------------------------------------------------

    "create propagates 409 alias-uniqueness from service" {
        every { aiModelGuard.canCreateVerdict(any(), any()) } returns AiModelGuard.CreateVerdict.Ok
        every { aiModelService.create(any()) } throws ResponseStatusException(HttpStatus.CONFLICT, "alias conflict")

        val ex = shouldThrow<ResponseStatusException> {
            withAuth { controller.create(resource()) }
        }
        ex.statusCode.value() shouldBe 409
    }

    // -------------------------------------------------------------------------
    // 12) create — SF4 : body userId/namespaceId silently stripped (toDomain forces null)
    // -------------------------------------------------------------------------

    "create with body userId and namespaceId set — they are stripped before service call (SF4)" {
        val captured = slot<AiModel>()
        every { aiModelGuard.canCreateVerdict(any(), any()) } returns AiModelGuard.CreateVerdict.Ok
        every { aiModelService.create(capture(captured)) } answers { firstArg() }

        withAuth {
            controller.create(
                resource(
                    nsId = namespaceId,
                    uId = aliceId,
                    apiModelName = "claude-haiku-4-5",
                ),
            )
        }

        captured.captured.namespaceId shouldBe null
        captured.captured.userId shouldBe null
    }

    // -------------------------------------------------------------------------
    // 13) create — SF3 : malformed auth.name throws 401 before verdict
    // -------------------------------------------------------------------------

    "create with malformed auth name throws 401 BEFORE verdict is evaluated (SF3)" {
        val badAuth = UsernamePasswordAuthenticationToken("not-a-uuid", "n/a", emptyList())
        val previous = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication = badAuth
        every { userService.getCurrentUser() } throws ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authentication identifier")
        try {
            val ex = shouldThrow<ResponseStatusException> {
                controller.create(resource())
            }
            ex.statusCode shouldBe HttpStatus.UNAUTHORIZED
            // Guard must never have been consulted
            verify(exactly = 0) { aiModelGuard.canCreateVerdict(any(), any()) }
        } finally {
            SecurityContextHolder.getContext().authentication = previous
            every { userService.getCurrentUser() } returns aliceUser()
        }
    }

    // -------------------------------------------------------------------------
    // 14) create — SF8 : race window — provider soft-deleted after guard returns Ok
    // -------------------------------------------------------------------------

    "create race window — parent soft-deleted after verdict Ok — ResourceNotFoundException becomes AccessDeniedException (SF8)" {
        every { aiModelGuard.canCreateVerdict(any(), any()) } returns AiModelGuard.CreateVerdict.Ok
        every { aiModelService.create(any()) } throws ResourceNotFoundException("provider gone")

        shouldThrow<AccessDeniedException> {
            withAuth { controller.create(resource()) }
        }
    }

    // -------------------------------------------------------------------------
    // 15) list — NS-shared mode returns NS-shared models (userId == null)
    // -------------------------------------------------------------------------

    "list NS-shared mode returns models with userId null filtered from findFiltered" {
        val rows = listOf(
            model(nsId = namespaceId, uId = null, apiModelName = "NS-A"),
            model(nsId = namespaceId, uId = null, apiModelName = "NS-B"),
        )
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        } returns true
        every { aiModelService.findFiltered(any(), any(), any(), any(), any(), any(), any()) } returns rows

        val resp = controller.list(
            namespaceId = namespaceId.toString(),
            userId = null,
            aiProviderId = null,
            page = 0,
            size = 20,
            auth = authFor(aliceId),
        )

        resp.content.map { it.apiModelName } shouldContainExactlyInAnyOrder listOf("NS-A", "NS-B")
        resp.totalElements shouldBe 2
    }

    // -------------------------------------------------------------------------
    // 16) list — user-scope mode
    // -------------------------------------------------------------------------

    "list user-scope mode returns only caller's models" {
        val rows = listOf(
            model(nsId = null, uId = aliceId, apiModelName = "GLOBAL"),
            model(nsId = namespaceId, uId = aliceId, apiModelName = "NS"),
        )
        every { aiModelService.findFiltered(any(), any(), any(), any(), any(), any(), any()) } returns rows

        val resp = controller.list(
            namespaceId = null,
            userId = "me",
            aiProviderId = null,
            page = 0,
            size = 20,
            auth = authFor(aliceId),
        )

        resp.totalElements shouldBe 2
        resp.content.map { it.apiModelName } shouldContainExactlyInAnyOrder listOf("GLOBAL", "NS")
    }

    // -------------------------------------------------------------------------
    // 17) list — aiProviderId filter with canSeeProvider true
    // -------------------------------------------------------------------------

    "list with aiProviderId filter and canSeeProvider true returns filtered models" {
        val rows = listOf(
            model(nsId = namespaceId, uId = null, apiModelName = "M1"),
            model(nsId = namespaceId, uId = null, apiModelName = "M2"),
        )
        every { aiModelGuard.canSeeProvider(aiProviderId, any()) } returns true
        every { aiModelService.findFiltered(any(), any(), any(), any(), any(), any(), any()) } returns rows

        val resp = controller.list(
            namespaceId = null,
            userId = null,
            aiProviderId = aiProviderId,
            page = 0,
            size = 20,
            auth = authFor(aliceId),
        )

        resp.totalElements shouldBe 2
        resp.content.map { it.apiModelName } shouldContainExactlyInAnyOrder listOf("M1", "M2")
    }

    // -------------------------------------------------------------------------
    // 18) list — NS-shared without READ returns empty list (F1 pattern)
    // -------------------------------------------------------------------------

    "list NS-shared without READ on namespace returns empty list and does not call service (F1)" {
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        } returns false
        every { aiModelService.findFiltered(any(), any(), any(), any(), any(), any(), any()) } returns emptyList()

        val resp = controller.list(
            namespaceId = namespaceId.toString(),
            userId = null,
            aiProviderId = null,
            page = 0,
            size = 20,
            auth = authFor(aliceId),
        )

        resp.content shouldBe emptyList()
        resp.totalElements shouldBe 0
        verify(exactly = 1) { aiModelService.findFiltered(any(), any(), any(), any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // 19) list — userId=<uuid> rejected with 400
    // -------------------------------------------------------------------------

    "list with explicit UUID as userId param is rejected with 400 ResponseStatusException" {
        val ex = shouldThrow<ResponseStatusException> {
            controller.list(
                namespaceId = null,
                userId = UUID.randomUUID().toString(),
                aiProviderId = null,
                page = 0,
                size = 20,
                auth = authFor(aliceId),
            )
        }
        ex.statusCode.value() shouldBe 400
    }

    // -------------------------------------------------------------------------
    // 20) update — preserves immutable fields
    // -------------------------------------------------------------------------

    "update preserves immutable fields (id, aiProviderId, namespaceId, userId) — captures slot" {
        val existingId = UUID.randomUUID()
        val existingProviderId = UUID.randomUUID()
        val existing = model(id = existingId, providerId = existingProviderId, nsId = namespaceId, uId = aliceId)
        val captured = slot<AiModel>()
        every { aiModelService.findById(existingId) } returns existing
        every { aiModelService.update(capture(captured)) } answers { firstArg() }

        controller.update(
            id = existingId,
            resource = resource(
                id = UUID.randomUUID(),
                providerId = UUID.randomUUID(),
                nsId = UUID.randomUUID(),
                uId = UUID.randomUUID(),
                apiModelName = "gpt-4o",
            ),
        )

        captured.captured.metadata.id shouldBe existingId
        captured.captured.aiProviderId shouldBe existingProviderId
        captured.captured.namespaceId shouldBe namespaceId
        captured.captured.userId shouldBe aliceId
        captured.captured.apiModelName shouldBe "gpt-4o"
    }

    // -------------------------------------------------------------------------
    // 21) getById — happy path
    // -------------------------------------------------------------------------

    "getById returns resource (happy path)" {
        val m = model()
        every { aiModelService.findById(m.metadata.id) } returns m

        val r = controller.getById(m.metadata.id)

        r.id shouldBe m.metadata.id
        r.apiModelName shouldBe "claude-haiku-4-5"
    }

    // -------------------------------------------------------------------------
    // 22) getByIds — ownership branch for non-admin caller
    // -------------------------------------------------------------------------

    "getByIds with ownership branch — user-owned rows visible even without membership edge" {
        val ownedId = UUID.randomUUID()
        val ownedModel = model(id = ownedId, nsId = null, uId = aliceId)
        val foreignId = UUID.randomUUID()
        val foreignModel = model(id = foreignId, nsId = namespaceId, uId = UUID.randomUUID())

        every { userService.getCurrentUser() } returns aliceUser(admin = false)
        every { permissionService.filterVisibleIds(aliceId.toString(), EntityType.AI_MODEL, any(), Action.READ) } returns emptySet()
        every { aiModelService.findByIds(listOf(ownedId, foreignId)) } returns listOf(ownedModel, foreignModel)

        val result = withAuth {
            controller.getByIds(listOf(ownedId, foreignId))
        }

        // owned model visible via ownership branch; foreign model not in membership and not owned -> excluded
        result.map { it.id } shouldBe listOf(ownedId)
    }

    // -------------------------------------------------------------------------
    // 23) listByParent — hard-break stub throws ResourceNotFoundException
    // -------------------------------------------------------------------------

    "listByParent throws ResourceNotFoundException (hard-break stub)" {
        shouldThrow<ResourceNotFoundException> {
            controller.listByParent(aiProviderId)
        }
    }
})
