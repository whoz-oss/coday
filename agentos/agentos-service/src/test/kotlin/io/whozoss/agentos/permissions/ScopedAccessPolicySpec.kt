package io.whozoss.agentos.permissions

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.security.access.AccessDeniedException
import java.util.UUID

/**
 * The single definition of the overlay-entity access predicate: *a membership or permission edge
 * grants access, or the caller owns the row*.
 *
 * [IntegrationConfig] is used as the concrete [io.whozoss.agentos.sdk.entity.ScopedEntity]
 * throughout — the policy never looks at anything beyond `(namespaceId, userId)` and the entity
 * id, so any overlay entity would do.
 *
 * [PermissionService] is deliberately NOT relaxed: `hasPermission` is stubbed per test so each
 * case states exactly what the permission graph grants, and the `verify(exactly = 0)` assertions
 * below can prove that the Super Admin and empty-input paths never reach it at all.
 */
class ScopedAccessPolicySpec : StringSpec({

    val callerId = UUID.randomUUID()
    val otherUserId = UUID.randomUUID()
    val namespaceId = UUID.randomUUID()

    fun human(isAdmin: Boolean = false): Caller = Caller.Human(callerId, isAdmin)

    fun config(
        nsId: UUID? = namespaceId,
        userId: UUID? = null,
        name: String = "JIRA_PROD",
    ) = IntegrationConfig(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        namespaceId = nsId,
        userId = userId,
        name = name,
        integrationType = "JIRA",
    )

    fun denyAll(): PermissionService = mockk<PermissionService>().also {
        every { it.hasPermission(any(), any(), any(), any()) } returns false
        every { it.filterVisibleIds(any(), any(), any(), any()) } returns emptySet()
    }

    // -------------------------------------------------------------------------
    // canAccess
    // -------------------------------------------------------------------------

    "a Super Admin is granted access without the permission graph being consulted" {
        val permissions = denyAll()
        val policy = ScopedAccessPolicy(permissions)
        val entity = config(userId = otherUserId)

        policy.canAccess(human(isAdmin = true), EntityType.INTEGRATION_CONFIG, entity, Action.WRITE) shouldBe true

        verify(exactly = 0) { permissions.hasPermission(any(), any(), any(), any()) }
    }

    "the owner is granted access with no permission edge at all" {
        val policy = ScopedAccessPolicy(denyAll())
        val own = config(nsId = null, userId = callerId)

        policy.canAccess(human(), EntityType.INTEGRATION_CONFIG, own, Action.WRITE) shouldBe true
    }

    "a non-owner without a permission edge is refused" {
        val policy = ScopedAccessPolicy(denyAll())
        val foreign = config(nsId = null, userId = otherUserId)

        policy.canAccess(human(), EntityType.INTEGRATION_CONFIG, foreign, Action.READ) shouldBe false
    }

    "a permission edge grants access to a row the caller does not own" {
        val permissions = denyAll()
        val foreign = config(nsId = null, userId = otherUserId)
        every {
            permissions.hasPermission(callerId.toString(), EntityType.INTEGRATION_CONFIG, foreign.id.toString(), Action.READ)
        } returns true
        val policy = ScopedAccessPolicy(permissions)

        policy.canAccess(human(), EntityType.INTEGRATION_CONFIG, foreign, Action.READ) shouldBe true
    }

    // -------------------------------------------------------------------------
    // requireAccess
    // -------------------------------------------------------------------------

    "requireAccess throws for a non-owner without a permission edge" {
        val policy = ScopedAccessPolicy(denyAll())
        val foreign = config(nsId = null, userId = otherUserId)

        shouldThrow<AccessDeniedException> {
            policy.requireAccess(human(), EntityType.INTEGRATION_CONFIG, foreign, Action.DELETE)
        }
    }

    "requireAccess passes for the owner" {
        val policy = ScopedAccessPolicy(denyAll())
        val own = config(nsId = null, userId = callerId)

        policy.requireAccess(human(), EntityType.INTEGRATION_CONFIG, own, Action.DELETE)
    }

    // -------------------------------------------------------------------------
    // requireAdminForPlatform
    // -------------------------------------------------------------------------

    "a platform-scoped row is refused to a non-admin" {
        val policy = ScopedAccessPolicy(denyAll())
        val platform = config(nsId = null, userId = null)

        shouldThrow<AccessDeniedException> {
            policy.requireAdminForPlatform(human(isAdmin = false), "IntegrationConfig", platform)
        }
    }

    "a platform-scoped row is allowed to a Super Admin" {
        val policy = ScopedAccessPolicy(denyAll())
        val platform = config(nsId = null, userId = null)

        policy.requireAdminForPlatform(human(isAdmin = true), "IntegrationConfig", platform)
    }

    "a scoped row never triggers the platform guard, admin or not" {
        val policy = ScopedAccessPolicy(denyAll())

        listOf(
            config(nsId = namespaceId, userId = null),
            config(nsId = null, userId = callerId),
            config(nsId = namespaceId, userId = callerId),
        ).forEach { scoped ->
            policy.requireAdminForPlatform(human(isAdmin = false), "IntegrationConfig", scoped)
            policy.requireAdminForPlatform(human(isAdmin = true), "IntegrationConfig", scoped)
        }
    }

    // -------------------------------------------------------------------------
    // canReadNamespace / requireNamespacePermission
    // -------------------------------------------------------------------------

    "a Super Admin reads any namespace without the permission graph being consulted" {
        val permissions = denyAll()
        val policy = ScopedAccessPolicy(permissions)

        policy.canReadNamespace(human(isAdmin = true), namespaceId) shouldBe true

        verify(exactly = 0) { permissions.hasPermission(any(), any(), any(), any()) }
    }

    "canReadNamespace delegates to the permission graph for a regular caller" {
        val permissions = denyAll()
        val policy = ScopedAccessPolicy(permissions)

        policy.canReadNamespace(human(), namespaceId) shouldBe false

        every {
            permissions.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        } returns true

        policy.canReadNamespace(human(), namespaceId) shouldBe true
    }

    "requireNamespacePermission names the entity, the namespace and the required action" {
        val policy = ScopedAccessPolicy(denyAll())

        val error = shouldThrow<AccessDeniedException> {
            policy.requireNamespacePermission(human(), namespaceId, Action.WRITE, "IntegrationConfig")
        }

        error.message shouldBe "Cannot create IntegrationConfig in namespace $namespaceId (WRITE required)"
    }

    // -------------------------------------------------------------------------
    // filterVisible
    // -------------------------------------------------------------------------

    "filterVisible short-circuits on an empty batch without consulting the permission service" {
        val permissions = denyAll()
        val policy = ScopedAccessPolicy(permissions)

        policy.filterVisible(human(), EntityType.INTEGRATION_CONFIG, emptyList()).shouldBeEmpty()

        verify(exactly = 0) { permissions.filterVisibleIds(any(), any(), any(), any()) }
    }

    "filterVisible returns everything for a Super Admin without consulting the permission service" {
        val permissions = denyAll()
        val policy = ScopedAccessPolicy(permissions)
        val rows = listOf(config(userId = otherUserId), config(nsId = null, userId = null))

        policy.filterVisible(human(isAdmin = true), EntityType.INTEGRATION_CONFIG, rows) shouldContainExactlyInAnyOrder
            rows.map { it.id }

        verify(exactly = 0) { permissions.filterVisibleIds(any(), any(), any(), any()) }
    }

    "filterVisible unions the permission-filtered ids with the rows the caller owns" {
        val permissions = denyAll()
        val own = config(nsId = null, userId = callerId, name = "OWN")
        val granted = config(nsId = null, userId = otherUserId, name = "GRANTED")
        val hidden = config(nsId = null, userId = otherUserId, name = "HIDDEN")
        every {
            permissions.filterVisibleIds(callerId.toString(), EntityType.INTEGRATION_CONFIG, any(), Action.READ)
        } returns setOf(granted.id.toString())
        val policy = ScopedAccessPolicy(permissions)

        policy.filterVisible(human(), EntityType.INTEGRATION_CONFIG, listOf(own, granted, hidden)) shouldContainExactlyInAnyOrder
            listOf(own.id, granted.id)
    }

    "filterVisible drops a non-UUID id returned by the permission service rather than throwing" {
        val permissions = denyAll()
        val granted = config(nsId = null, userId = otherUserId, name = "GRANTED")
        every {
            permissions.filterVisibleIds(callerId.toString(), EntityType.INTEGRATION_CONFIG, any(), Action.READ)
        } returns setOf(granted.id.toString(), "not-a-uuid")
        val policy = ScopedAccessPolicy(permissions)

        policy.filterVisible(human(), EntityType.INTEGRATION_CONFIG, listOf(granted)) shouldBe setOf(granted.id)
    }
})
