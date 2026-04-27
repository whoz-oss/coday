package io.whozoss.agentos.security.declarative

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import java.util.UUID

/**
 * Unit tests for [AgentOsPermissionEvaluator].
 */
class AgentOsPermissionEvaluatorSpec : StringSpec({

    val permissionService = mockk<PermissionService>()
    val evaluator = AgentOsPermissionEvaluator(permissionService)

    val userId = UUID.randomUUID().toString()
    val auth: Authentication = UsernamePasswordAuthenticationToken(userId, null, emptyList())

    "hasPermission with (id, type, permission) delegates to PermissionService" {
        val targetId = UUID.randomUUID()
        every {
            permissionService.hasPermission(userId, "AgentConfig", targetId.toString(), Action.READ)
        } returns true

        evaluator.hasPermission(auth, targetId, "AgentConfig", "READ") shouldBe true

        verify(exactly = 1) {
            permissionService.hasPermission(userId, "AgentConfig", targetId.toString(), Action.READ)
        }
    }

    "hasPermission propagates the permission denial" {
        val targetId = UUID.randomUUID()
        every {
            permissionService.hasPermission(userId, "Namespace", targetId.toString(), Action.WRITE)
        } returns false

        evaluator.hasPermission(auth, targetId, "Namespace", "WRITE") shouldBe false
    }

    "hasPermission returns false for an unknown permission string (typo) instead of throwing" {
        val targetId = UUID.randomUUID()

        evaluator.hasPermission(auth, targetId, "Case", "MAGIC") shouldBe false

        verify(exactly = 0) { permissionService.hasPermission(any(), any(), any(), any()) }
    }

    "hasPermission returns false when authentication is null" {
        evaluator.hasPermission(null, UUID.randomUUID(), "Case", "READ") shouldBe false
    }

    "hasPermission returns false when targetId is null" {
        evaluator.hasPermission(auth, null, "Case", "READ") shouldBe false
    }

    "hasPermission returns false when targetType is null" {
        evaluator.hasPermission(auth, UUID.randomUUID(), null, "READ") shouldBe false
    }

    "hasPermission returns false when authentication.name is null" {
        val nameless = mockk<Authentication>(relaxed = true) {
            every { name } returns null
        }
        evaluator.hasPermission(nameless, UUID.randomUUID(), "Case", "READ") shouldBe false
    }

    "hasPermission(domainObject) maps Entity.id and class.simpleName" {
        val id = UUID.randomUUID()
        val entity = TestEntity(id)
        every {
            permissionService.hasPermission(userId, "TestEntity", id.toString(), Action.DELETE)
        } returns true

        evaluator.hasPermission(auth, entity, "DELETE") shouldBe true
    }

    "hasPermission(domainObject) returns false when targetDomainObject is not an Entity" {
        evaluator.hasPermission(auth, "not-an-entity", "READ") shouldBe false
        evaluator.hasPermission(auth, null, "READ") shouldBe false
    }
})

private data class TestEntity(
    val identifier: UUID,
) : Entity {
    override val metadata: EntityMetadata = EntityMetadata(id = identifier)
}
