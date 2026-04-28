package io.whozoss.agentos.namespace

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import java.util.UUID

/**
 * Unit tests for [NamespaceServiceImpl.findIdsVisibleTo] — the thin typed
 * wrapper added to encapsulate the `"Namespace"` entityType literal and the
 * `String → UUID` conversion that previously lived in `NamespaceController.listAll`.
 *
 * CRUD operations of [NamespaceServiceImpl] are pure delegates to the repository
 * and are covered transitively by `Abstract*PersistenceSpec` against a real
 * Neo4j harness — no need to duplicate them here.
 */
class NamespaceServiceImplSpec : StringSpec({
    val namespaceRepository = mockk<NamespaceRepository>(relaxed = true)
    val permissionService = mockk<PermissionService>()
    val service = NamespaceServiceImpl(namespaceRepository, permissionService)

    val userId = UUID.randomUUID().toString()

    beforeTest { clearAllMocks() }

    "findIdsVisibleTo delegates to PermissionService with entityType='Namespace' and the given action" {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        every {
            permissionService.listEntitiesForUser(userId, EntityType.NAMESPACE,Action.READ)
        } returns listOf(id1.toString(), id2.toString())

        val result = service.findIdsVisibleTo(userId, Action.READ)

        result shouldContainExactly listOf(id1, id2)
    }

    "findIdsVisibleTo passes Action.WRITE through to the permission lookup" {
        val id1 = UUID.randomUUID()
        every {
            permissionService.listEntitiesForUser(userId, EntityType.NAMESPACE,Action.WRITE)
        } returns listOf(id1.toString())

        service.findIdsVisibleTo(userId, Action.WRITE) shouldContainExactly listOf(id1)
    }

    "findIdsVisibleTo returns empty list when permission lookup yields no entities" {
        every {
            permissionService.listEntitiesForUser(userId, EntityType.NAMESPACE,Action.READ)
        } returns emptyList()

        service.findIdsVisibleTo(userId, Action.READ) shouldBe emptyList()
    }

    "findIdsVisibleTo drops malformed UUID strings defensively" {
        val valid = UUID.randomUUID()
        every {
            permissionService.listEntitiesForUser(userId, EntityType.NAMESPACE,Action.READ)
        } returns listOf(valid.toString(), "not-a-uuid", "")

        val result = service.findIdsVisibleTo(userId, Action.READ)

        result shouldHaveSize 1
        result.first() shouldBe valid
    }
})
