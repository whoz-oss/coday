package io.whozoss.agentos.sdk.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class PermissionContextSpec : StringSpec() {
    init {
        "creates with required fields only" {
            val userId = UUID.randomUUID()
            val ctx = PermissionContext(userId = userId, isRoot = false)
            ctx.userId shouldBe userId
            ctx.isRoot shouldBe false
            ctx.namespaceId shouldBe null
            ctx.namespaceRole shouldBe null
            ctx.caseId shouldBe null
            ctx.caseRole shouldBe null
            ctx.toolRestriction shouldBe null
        }
        "creates with all fields" {
            val userId = UUID.randomUUID()
            val nsId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val restriction = ToolRestriction(ToolRestrictionMode.WHITELIST, setOf("tool1"))
            val ctx = PermissionContext(
                userId = userId,
                isRoot = true,
                namespaceId = nsId,
                namespaceRole = NamespaceRole.ADMIN,
                caseId = caseId,
                caseRole = CaseRole.PARTICIPANT,
                toolRestriction = restriction,
            )
            ctx.userId shouldBe userId
            ctx.isRoot shouldBe true
            ctx.namespaceId shouldBe nsId
            ctx.namespaceRole shouldBe NamespaceRole.ADMIN
            ctx.caseId shouldBe caseId
            ctx.caseRole shouldBe CaseRole.PARTICIPANT
            ctx.toolRestriction shouldBe restriction
        }
        "is immutable via data class copy" {
            val original = PermissionContext(userId = UUID.randomUUID(), isRoot = false)
            val modified = original.copy(isRoot = true)
            original.isRoot shouldBe false
            modified.isRoot shouldBe true
        }
        "structural equality works" {
            val userId = UUID.randomUUID()
            val ctx1 = PermissionContext(userId = userId, isRoot = false)
            val ctx2 = PermissionContext(userId = userId, isRoot = false)
            (ctx1 == ctx2) shouldBe true
        }
    }
}
