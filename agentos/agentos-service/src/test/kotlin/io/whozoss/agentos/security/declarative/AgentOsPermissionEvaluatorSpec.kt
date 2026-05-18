package io.whozoss.agentos.security.declarative

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
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
    val ownershipResolver = mockk<OwnershipResolver>(relaxed = true) {
        every { supportedTypes } returns setOf(EntityType.AI_PROVIDER, EntityType.INTEGRATION_CONFIG)
    }
    val evaluator = AgentOsPermissionEvaluator(permissionService, ownershipResolver)

    val userId = UUID.randomUUID().toString()
    val auth: Authentication = UsernamePasswordAuthenticationToken(userId, null, emptyList())

    "hasPermission with (id, type, permission) delegates to PermissionService" {
        val targetId = UUID.randomUUID()
        every {
            permissionService.hasPermission(userId, EntityType.AGENT_CONFIG, targetId.toString(), Action.READ)
        } returns true

        evaluator.hasPermission(auth, targetId, "AgentConfig", "READ") shouldBe true

        verify(exactly = 1) {
            permissionService.hasPermission(userId, EntityType.AGENT_CONFIG, targetId.toString(), Action.READ)
        }
    }

    "hasPermission propagates the permission denial for non-scope-aware entity" {
        val targetId = UUID.randomUUID()
        every {
            permissionService.hasPermission(userId, EntityType.NAMESPACE, targetId.toString(), Action.WRITE)
        } returns false

        evaluator.hasPermission(auth, targetId, "Namespace", "WRITE") shouldBe false

        // Namespace is NOT in OWNERSHIP_AWARE_TYPES — ownership branch must NOT be consulted.
        verify(exactly = 0) { ownershipResolver.resolveOwner(any(), any()) }
    }

    "ownership branch allows scope-aware entity when caller owns it (AiProvider)" {
        val targetId = UUID.randomUUID()
        every {
            permissionService.hasPermission(userId, EntityType.AI_PROVIDER, targetId.toString(), Action.READ)
        } returns false
        every { ownershipResolver.resolveOwner(EntityType.AI_PROVIDER, targetId) } returns UUID.fromString(userId)

        evaluator.hasPermission(auth, targetId, "AiProvider", "READ") shouldBe true
    }

    "ownership branch denies scope-aware entity when caller is NOT the owner (AiProvider)" {
        val targetId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        every {
            permissionService.hasPermission(userId, EntityType.AI_PROVIDER, targetId.toString(), Action.WRITE)
        } returns false
        every { ownershipResolver.resolveOwner(EntityType.AI_PROVIDER, targetId) } returns otherUserId

        evaluator.hasPermission(auth, targetId, "AiProvider", "WRITE") shouldBe false
    }

    "ownership branch is short-circuited when permissionService returns true (super-admin / explicit grant)" {
        val targetId = UUID.randomUUID()
        every {
            permissionService.hasPermission(userId, EntityType.AI_PROVIDER, targetId.toString(), Action.READ)
        } returns true

        evaluator.hasPermission(auth, targetId, "AiProvider", "READ") shouldBe true

        // AC14 — super-admin / explicit grant short-circuits ownership lookup, avoiding the extra findById.
        verify(exactly = 0) { ownershipResolver.resolveOwner(any(), any()) }
    }

    "ownership branch allows scope-aware entity when caller owns it (IntegrationConfig)" {
        val targetId = UUID.randomUUID()
        every {
            permissionService.hasPermission(userId, EntityType.INTEGRATION_CONFIG, targetId.toString(), Action.DELETE)
        } returns false
        every { ownershipResolver.resolveOwner(EntityType.INTEGRATION_CONFIG, targetId) } returns UUID.fromString(userId)

        evaluator.hasPermission(auth, targetId, "IntegrationConfig", "DELETE") shouldBe true
    }

    "ownership branch returns false when targetId is not a valid UUID (defence-in-depth)" {
        val notAUuid = "not-a-uuid"
        every {
            permissionService.hasPermission(userId, EntityType.AI_PROVIDER, notAUuid, Action.READ)
        } returns false

        evaluator.hasPermission(auth, notAUuid, "AiProvider", "READ") shouldBe false

        // resolveOwner is wrapped in runCatching — invalid UUID swallowed, returns false.
        verify(exactly = 0) { ownershipResolver.resolveOwner(any(), any()) }
    }

    "ownership branch is NOT consulted for non-scope-aware entities (AgentConfig, Case, …)" {
        val targetId = UUID.randomUUID()
        every {
            permissionService.hasPermission(userId, EntityType.AGENT_CONFIG, targetId.toString(), Action.READ)
        } returns false

        evaluator.hasPermission(auth, targetId, "AgentConfig", "READ") shouldBe false

        // AC10 — ownership leakage prevented : only AI_PROVIDER and INTEGRATION_CONFIG trigger the branch.
        verify(exactly = 0) { ownershipResolver.resolveOwner(any(), any()) }
    }

    "hasPermission returns false for an unknown entity label (typo) instead of throwing" {
        val targetId = UUID.randomUUID()

        evaluator.hasPermission(auth, targetId, "AgenConfig", "READ") shouldBe false

        verify(exactly = 0) { permissionService.hasPermission(any(), any(), any(), any()) }
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
        val entity = AgentConfig(id)
        every {
            permissionService.hasPermission(userId, EntityType.AGENT_CONFIG, id.toString(), Action.DELETE)
        } returns true

        evaluator.hasPermission(auth, entity, "DELETE") shouldBe true
    }

    "hasPermission(domainObject) returns false when targetDomainObject is not an Entity" {
        evaluator.hasPermission(auth, "not-an-entity", "READ") shouldBe false
        evaluator.hasPermission(auth, null, "READ") shouldBe false
    }
})

private data class AgentConfig(
    val identifier: UUID,
) : Entity {
    override val metadata: EntityMetadata = EntityMetadata(id = identifier)
}
