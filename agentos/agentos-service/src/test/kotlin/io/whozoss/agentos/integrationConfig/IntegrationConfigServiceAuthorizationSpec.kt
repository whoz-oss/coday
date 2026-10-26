package io.whozoss.agentos.integrationConfig

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
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
 * Authorization contract of [IntegrationConfigServiceImpl]'s `*ForCaller` surface.
 *
 * These rules used to live on [IntegrationConfigController] — as `@PreAuthorize` SpEL, as
 * explicit guards in the controller body, and inside the
 * [io.whozoss.agentos.entity.ScopedOwnershipCrudDelegate] it built. They were therefore
 * reachable only through HTTP, which is why every non-HTTP caller (agent tools, schedulers)
 * had to re-derive them. They now belong to the service, take an explicit `callerId`, and are
 * asserted here against the service directly — no controller, no Spring context, no
 * [org.springframework.security.core.context.SecurityContextHolder].
 *
 * [permissionService] is deliberately NOT relaxed: `hasPermission` defaults to `false` so every
 * test states exactly what it grants. A relaxed mock would silently return `false` too, but it
 * would also hide a call made against an unexpected (entity, action) pair.
 *
 * End-to-end rendering of a refusal (403 vs 404 via `@HideOnAccessDenied`) is covered by
 * [IntegrationConfigControllerMvcIntegrationSpec] and [IntegrationConfigCrossUserIsolationSpec].
 */
class IntegrationConfigServiceAuthorizationSpec : StringSpec({

    val callerId = UUID.randomUUID()
    val otherUserId = UUID.randomUUID()
    val namespaceId = UUID.randomUUID()

    fun user(id: UUID, isAdmin: Boolean) = User(
        metadata = EntityMetadata(id = id),
        externalId = "user-$id@example.com",
        email = "user-$id@example.com",
        isAdmin = isAdmin,
    )

    /**
     * One service and its mocks per test. The repository is the in-memory fixture, so a test can
     * seed a row through the **unchecked** [IntegrationConfigService.create] to set up a state the
     * authorized surface would refuse to create (a denied type in user scope, a platform row).
     *
     * The caller's admin flag is not a constructor argument: it is applied by [fixture], which
     * stubs `userService.findById(callerId)`, because that lookup is exactly how the service
     * decides super-admin status.
     */
    class Fixture {
        val permissionService = mockk<PermissionService>()
        val userService = mockk<UserService>()
        val namespaceService = mockk<NamespaceService>(relaxed = true)
        val repository = InMemoryIntegrationConfigRepository()
        val service = IntegrationConfigServiceImpl(
            repository,
            IntegrationConfigMergeStrategy(),
            permissionService = permissionService,
            userService = userService,
            namespaceService = namespaceService,
            scopePolicy = IntegrationConfigScopePolicy(IntegrationsProperties()),
        )

        init {
            every { permissionService.hasPermission(any(), any(), any(), any()) } returns false
            every { permissionService.filterVisibleIds(any(), any(), any(), any()) } returns emptySet()
            every { userService.findById(any()) } returns null
        }
    }

    fun fixture(callerIsAdmin: Boolean = false): Fixture = Fixture().also {
        every { it.userService.findById(callerId) } returns user(callerId, callerIsAdmin)
        every { it.namespaceService.findById(namespaceId) } returns Namespace(
            metadata = EntityMetadata(id = namespaceId),
            externalId = "ns-$namespaceId",
            name = "ns",
        )
    }

    fun config(
        nsId: UUID? = namespaceId,
        userId: UUID? = null,
        name: String = "JIRA_PROD",
        integrationType: String = "JIRA",
    ) = IntegrationConfig(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        namespaceId = nsId,
        userId = userId,
        name = name,
        integrationType = integrationType,
    )

    fun changes(name: String = "RENAMED") = IntegrationConfigUpdate(
        name = name,
        description = "updated",
        parameters = null,
        authSettingName = null,
        autoGrant = false,
    )

    fun Fixture.grantNamespace(action: Action) {
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), action)
        } returns true
    }

    fun Fixture.grantEntity(id: UUID, action: Action) {
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.INTEGRATION_CONFIG, id.toString(), action)
        } returns true
    }

    // -------------------------------------------------------------------------
    // createForCaller — mass-assignment guard on the requested owner
    // -------------------------------------------------------------------------

    "create rejects body.userId mismatched with the caller" {
        val f = fixture()

        shouldThrow<BadRequestException> {
            f.service.createForCaller(callerId, config(nsId = null, userId = otherUserId))
        }
        f.repository.findByUserId(otherUserId).shouldBeEmpty()
    }

    // -------------------------------------------------------------------------
    // createForCaller — platform scope (null, null) is Super Admin only
    // -------------------------------------------------------------------------

    "create platform scope by a non-admin is denied and persists nothing" {
        val f = fixture(callerIsAdmin = false)

        shouldThrow<AccessDeniedException> {
            f.service.createForCaller(callerId, config(nsId = null, userId = null))
        }
        f.repository.findPlatform().shouldBeEmpty()
    }

    "create platform scope by a Super Admin succeeds without any namespace permission query" {
        val f = fixture(callerIsAdmin = true)

        val saved = f.service.createForCaller(callerId, config(nsId = null, userId = null))

        saved.namespaceId shouldBe null
        saved.userId shouldBe null
        verify(exactly = 0) { f.permissionService.hasPermission(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // createForCaller — user-scope denial for network-reaching integration types
    // -------------------------------------------------------------------------

    "create user-global config of a user-scope-denied type is refused before any persistence" {
        val f = fixture()

        shouldThrow<AccessDeniedException> {
            f.service.createForCaller(callerId, config(nsId = null, userId = callerId, integrationType = "MCP_HTTP"))
        }
        f.repository.findByUserId(callerId).shouldBeEmpty()
    }

    "create user-namespace config of a denied type is refused even with namespace READ" {
        val f = fixture()
        f.grantNamespace(Action.READ)

        shouldThrow<AccessDeniedException> {
            f.service.createForCaller(callerId, config(userId = callerId, integrationType = "HTTP_API"))
        }
        f.repository.findByUserId(callerId).shouldBeEmpty()
    }

    "create NS-shared config of a denied type is still allowed with namespace WRITE" {
        val f = fixture()
        f.grantNamespace(Action.WRITE)

        val saved = f.service.createForCaller(callerId, config(userId = null, integrationType = "MCP_HTTP"))

        saved.userId shouldBe null
        saved.integrationType shouldBe "MCP_HTTP"
    }

    // -------------------------------------------------------------------------
    // createForCaller — per-scope namespace permission
    // -------------------------------------------------------------------------

    "create NS-shared requires WRITE on the namespace and persists with userId=null" {
        val f = fixture()
        f.grantNamespace(Action.WRITE)

        val saved = f.service.createForCaller(callerId, config(userId = null, name = "shared"))

        saved.namespaceId shouldBe namespaceId
        saved.userId shouldBe null
    }

    "create NS-shared without WRITE on the namespace is denied" {
        val f = fixture()

        shouldThrow<AccessDeniedException> {
            f.service.createForCaller(callerId, config(userId = null))
        }
        f.repository.findByNamespaceId(namespaceId).shouldBeEmpty()
    }

    "create user-namespace requires READ on the namespace" {
        val f = fixture()
        f.grantNamespace(Action.READ)

        val saved = f.service.createForCaller(callerId, config(userId = callerId))

        saved.namespaceId shouldBe namespaceId
        saved.userId shouldBe callerId
        verify(exactly = 1) {
            f.permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        }
    }

    "create user-namespace without READ on the namespace is denied" {
        val f = fixture()

        shouldThrow<AccessDeniedException> {
            f.service.createForCaller(callerId, config(userId = callerId))
        }
        f.repository.findByUserId(callerId).shouldBeEmpty()
    }

    "create user-global makes no namespace permission query" {
        val f = fixture()

        val saved = f.service.createForCaller(callerId, config(nsId = null, userId = callerId))

        saved.namespaceId shouldBe null
        saved.userId shouldBe callerId
        verify(exactly = 0) { f.permissionService.hasPermission(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // createForCaller — existence is checked AFTER authorization (no existence oracle)
    // -------------------------------------------------------------------------

    "create with a dangling namespaceId returns 404 once authorization passes" {
        val f = fixture()
        val unknownNs = UUID.randomUUID()
        every { f.namespaceService.findById(unknownNs) } returns null
        every {
            f.permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, unknownNs.toString(), Action.WRITE)
        } returns true

        shouldThrow<ResourceNotFoundException> {
            f.service.createForCaller(callerId, config(nsId = unknownNs, userId = null))
        }
    }

    "create with a dangling namespaceId surfaces as access denied for a non-member (no existence leak)" {
        val f = fixture()
        val unknownNs = UUID.randomUUID()
        every { f.namespaceService.findById(unknownNs) } returns null

        shouldThrow<AccessDeniedException> {
            f.service.createForCaller(callerId, config(nsId = unknownNs, userId = null))
        }
    }

    // -------------------------------------------------------------------------
    // getByIdForCaller — membership, ownership, and the unknown id
    // -------------------------------------------------------------------------

    "getById on an unknown id is 404" {
        val f = fixture()

        shouldThrow<ResourceNotFoundException> { f.service.getByIdForCaller(callerId, UUID.randomUUID()) }
    }

    "the owner reads their own row with no permission edge at all" {
        val f = fixture()
        val own = f.service.create(config(nsId = null, userId = callerId))

        f.service.getByIdForCaller(callerId, own.id).id shouldBe own.id
    }

    "a non-owner without permission is denied on read" {
        val f = fixture()
        val foreign = f.service.create(config(nsId = null, userId = otherUserId))

        shouldThrow<AccessDeniedException> { f.service.getByIdForCaller(callerId, foreign.id) }
    }

    "a non-owner without permission is denied on the export path (READ is not enough)" {
        val f = fixture()
        val foreign = f.service.create(config(nsId = null, userId = otherUserId))
        f.grantEntity(foreign.id, Action.READ)

        f.service.getByIdForCaller(callerId, foreign.id, Action.READ).id shouldBe foreign.id
        shouldThrow<AccessDeniedException> { f.service.getByIdForCaller(callerId, foreign.id, Action.WRITE) }
    }

    // -------------------------------------------------------------------------
    // updateForCaller
    // -------------------------------------------------------------------------

    "update on a non-existent id is 404" {
        val f = fixture()

        shouldThrow<ResourceNotFoundException> { f.service.updateForCaller(callerId, UUID.randomUUID(), changes()) }
    }

    "the owner updates their own row with no permission edge at all" {
        val f = fixture()
        val own = f.service.create(config(nsId = null, userId = callerId))

        f.service.updateForCaller(callerId, own.id, changes()).name shouldBe "RENAMED"
    }

    "a non-owner without permission is denied on update" {
        val f = fixture()
        val foreign = f.service.create(config(nsId = null, userId = otherUserId))

        shouldThrow<AccessDeniedException> { f.service.updateForCaller(callerId, foreign.id, changes()) }
        f.service.findById(foreign.id)!!.name shouldBe "JIRA_PROD"
    }

    "update preserves id, namespaceId, userId and integrationType (mass-assignment guard)" {
        val f = fixture()
        val own = f.service.create(config(userId = callerId, integrationType = "JIRA"))
        f.grantNamespace(Action.READ)

        val updated = f.service.updateForCaller(callerId, own.id, changes(name = "RENAMED"))

        updated.id shouldBe own.id
        updated.namespaceId shouldBe namespaceId
        updated.userId shouldBe callerId
        updated.integrationType shouldBe "JIRA"
        updated.name shouldBe "RENAMED"
        updated.description shouldBe "updated"
    }

    "update of an existing user-scoped row of a denied type is refused on the PERSISTED type" {
        // The row is seeded through the unchecked create: the authorized surface would never
        // have produced it. integrationType is immutable, so the persisted type is what decides.
        val f = fixture()
        val legacy = f.service.create(config(nsId = null, userId = callerId, integrationType = "MCP_STDIO"))

        shouldThrow<AccessDeniedException> { f.service.updateForCaller(callerId, legacy.id, changes()) }
        f.service.findById(legacy.id)!!.name shouldBe "JIRA_PROD"
    }

    "update of a platform row requires Super Admin even when the caller holds WRITE on it" {
        val f = fixture(callerIsAdmin = false)
        val platform = f.service.create(config(nsId = null, userId = null))
        f.grantEntity(platform.id, Action.WRITE)

        shouldThrow<AccessDeniedException> { f.service.updateForCaller(callerId, platform.id, changes()) }
    }

    "update of a platform row succeeds for a Super Admin" {
        val f = fixture(callerIsAdmin = true)
        val platform = f.service.create(config(nsId = null, userId = null))
        f.grantEntity(platform.id, Action.WRITE)

        f.service.updateForCaller(callerId, platform.id, changes()).name shouldBe "RENAMED"
    }

    // -------------------------------------------------------------------------
    // deleteForCaller
    // -------------------------------------------------------------------------

    "delete on a non-existent id is 404" {
        val f = fixture()

        shouldThrow<ResourceNotFoundException> { f.service.deleteForCaller(callerId, UUID.randomUUID()) }
    }

    "the owner deletes their own row with no permission edge at all" {
        val f = fixture()
        val own = f.service.create(config(nsId = null, userId = callerId))

        f.service.deleteForCaller(callerId, own.id)

        f.service.findById(own.id) shouldBe null
    }

    "a non-owner without permission is denied on delete" {
        val f = fixture()
        val foreign = f.service.create(config(nsId = null, userId = otherUserId))

        shouldThrow<AccessDeniedException> { f.service.deleteForCaller(callerId, foreign.id) }
        f.service.findById(foreign.id).shouldNotBeNull()
    }

    "delete of a platform row requires Super Admin even when the caller holds DELETE on it" {
        val f = fixture(callerIsAdmin = false)
        val platform = f.service.create(config(nsId = null, userId = null))
        f.grantEntity(platform.id, Action.DELETE)

        shouldThrow<AccessDeniedException> { f.service.deleteForCaller(callerId, platform.id) }
        f.service.findById(platform.id).shouldNotBeNull()
    }

    "delete of a platform row succeeds for a Super Admin" {
        val f = fixture(callerIsAdmin = true)
        val platform = f.service.create(config(nsId = null, userId = null))
        f.grantEntity(platform.id, Action.DELETE)

        f.service.deleteForCaller(callerId, platform.id)

        f.service.findById(platform.id) shouldBe null
    }

    // -------------------------------------------------------------------------
    // findByIdsForCaller
    // -------------------------------------------------------------------------

    "findByIds rejects a batch above the maximum" {
        val f = fixture()
        val tooMany = List(1001) { UUID.randomUUID() }

        shouldThrow<BadRequestException> { f.service.findByIdsForCaller(callerId, tooMany, withRemoved = false) }
    }

    "findByIds short-circuits on an empty batch without consulting the permission service" {
        val f = fixture()

        f.service.findByIdsForCaller(callerId, emptyList(), withRemoved = false).shouldBeEmpty()

        verify(exactly = 0) { f.permissionService.filterVisibleIds(any(), any(), any(), any()) }
    }

    "findByIds returns a row the caller owns even when the permission filter excludes it" {
        val f = fixture()
        val own = f.service.create(config(nsId = null, userId = callerId))
        val foreign = f.service.create(config(nsId = null, userId = otherUserId, name = "FOREIGN"))

        val result = f.service.findByIdsForCaller(callerId, listOf(own.id, foreign.id), withRemoved = false)

        result.map { it.id } shouldBe listOf(own.id)
    }

    "findByIds preserves input order and duplicates" {
        val f = fixture()
        val a = f.service.create(config(nsId = null, userId = callerId, name = "A"))
        val b = f.service.create(config(nsId = null, userId = callerId, name = "B"))

        val result = f.service.findByIdsForCaller(callerId, listOf(b.id, a.id, b.id), withRemoved = false)

        result.map { it.id } shouldBe listOf(b.id, a.id, b.id)
    }

    "findByIds as a Super Admin bypasses the permission filter entirely" {
        val f = fixture(callerIsAdmin = true)
        val foreign = f.service.create(config(nsId = null, userId = otherUserId))

        val result = f.service.findByIdsForCaller(callerId, listOf(foreign.id), withRemoved = false)

        result.map { it.id } shouldBe listOf(foreign.id)
        verify(exactly = 0) { f.permissionService.filterVisibleIds(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // findFilteredForCaller — the NS-shared branch hides, it does not refuse
    // -------------------------------------------------------------------------

    "findFiltered on a namespace the caller cannot read returns an empty list rather than throwing" {
        val f = fixture()
        f.service.create(config(userId = null))

        f.service
            .findFilteredForCaller(callerId, namespaceId, namespaceIsNone = false, userRequested = false)
            .shouldBeEmpty()
    }

    "findFiltered on a namespace the caller can read returns its shared rows" {
        val f = fixture()
        val shared = f.service.create(config(userId = null))
        f.grantNamespace(Action.READ)

        f.service
            .findFilteredForCaller(callerId, namespaceId, namespaceIsNone = false, userRequested = false)
            .map { it.id } shouldBe listOf(shared.id)
    }
})
