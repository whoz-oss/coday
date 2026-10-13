package io.whozoss.agentos.skill

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit spec for [SkillAuthorizationService], the SpEL fallback that makes nodeless
 * filesystem-backed skills reachable by `@PreAuthorize`.
 *
 * The cases that matter most are the non-widening pins: for a PERSISTED skill, [canWrite] and
 * [canDelete] must return false even for a namespace admin, so the primary graph-based
 * `hasPermission(#id, 'Skill', …)` branch stays the only authority on persisted skills.
 */
class SkillAuthorizationServiceUnitSpec : StringSpec({

    val skillService = mockk<SkillService>()
    val permissionService = mockk<PermissionService>()
    val service = SkillAuthorizationService(skillService, permissionService)

    val userId = UUID.randomUUID().toString()
    val namespaceId: UUID = UUID.randomUUID()

    // Stubs and call counts must not leak between cases: the `verify(exactly = 0)` assertions
    // below are about what a single call did, not about the whole spec.
    beforeTest { clearMocks(skillService, permissionService) }

    fun filesystemSkill(ns: UUID? = namespaceId) = Skill(
        metadata = EntityMetadata(id = UUID.randomUUID(), version = null),
        namespaceId = ns,
        name = "fs-skill",
        description = "desc",
        body = "body",
    )

    fun persistedSkill(ns: UUID? = namespaceId) = Skill(
        metadata = EntityMetadata(id = UUID.randomUUID(), version = 0L),
        namespaceId = ns,
        name = "db-skill",
        description = "desc",
        body = "body",
    )

    // -------------------------------------------------------------------------
    // canRead
    // -------------------------------------------------------------------------

    "canRead returns false for an unknown id (fail-closed)" {
        val unknownId = UUID.randomUUID()
        every { skillService.findById(unknownId, true) } returns null

        service.canRead(userId, unknownId) shouldBe false
    }

    "canRead returns true for a platform skill without consulting permissions" {
        val skill = persistedSkill(ns = null)
        every { skillService.findById(skill.metadata.id, true) } returns skill

        service.canRead(userId, skill.metadata.id) shouldBe true
        verify(exactly = 0) { permissionService.hasPermission(any(), any(), any(), any()) }
    }

    "canRead delegates to namespace READ for a namespace skill" {
        val skill = filesystemSkill()
        every { skillService.findById(skill.metadata.id, true) } returns skill
        every {
            permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        } returns true

        service.canRead(userId, skill.metadata.id) shouldBe true
    }

    // -------------------------------------------------------------------------
    // canWrite / canDelete — non-widening guard
    // -------------------------------------------------------------------------

    "canWrite returns false for a PERSISTED skill even when the caller is a namespace admin" {
        val skill = persistedSkill()
        every { skillService.findById(skill.metadata.id, true) } returns skill
        every { permissionService.hasPermission(any(), any(), any(), any()) } returns true

        service.canWrite(userId, skill.metadata.id) shouldBe false
        verify(exactly = 0) { permissionService.hasPermission(any(), any(), any(), any()) }
    }

    "canDelete returns false for a PERSISTED skill even when the caller is a namespace admin" {
        val skill = persistedSkill()
        every { skillService.findById(skill.metadata.id, true) } returns skill
        every { permissionService.hasPermission(any(), any(), any(), any()) } returns true

        service.canDelete(userId, skill.metadata.id) shouldBe false
        verify(exactly = 0) { permissionService.hasPermission(any(), any(), any(), any()) }
    }

    "canWrite requires namespace WRITE on a filesystem-backed skill" {
        val skill = filesystemSkill()
        every { skillService.findById(skill.metadata.id, true) } returns skill
        every {
            permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
        } returns true

        service.canWrite(userId, skill.metadata.id) shouldBe true
    }

    "canWrite returns false on a filesystem-backed skill when the caller only has namespace READ" {
        val skill = filesystemSkill()
        every { skillService.findById(skill.metadata.id, true) } returns skill
        every {
            permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
        } returns false

        service.canWrite(userId, skill.metadata.id) shouldBe false
    }

    "canDelete requires namespace DELETE on a filesystem-backed skill" {
        val skill = filesystemSkill()
        every { skillService.findById(skill.metadata.id, true) } returns skill
        every {
            permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.DELETE)
        } returns true

        service.canDelete(userId, skill.metadata.id) shouldBe true
    }

    "canWrite returns false for an unknown id (fail-closed)" {
        val unknownId = UUID.randomUUID()
        every { skillService.findById(unknownId, true) } returns null

        service.canWrite(userId, unknownId) shouldBe false
    }

    "canWrite returns false for a filesystem-backed skill with no namespace (fail-closed)" {
        val skill = filesystemSkill(ns = null)
        every { skillService.findById(skill.metadata.id, true) } returns skill

        service.canWrite(userId, skill.metadata.id) shouldBe false
    }
})
