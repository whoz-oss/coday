package io.whozoss.agentos.skill

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

class SkillToolGrantServiceUnitSpec : StringSpec({

    val service = SkillToolGrantService()

    val toolContext = mockk<ToolContext>(relaxed = true)

    val skill1 = Skill(
        metadata = EntityMetadata(),
        namespaceId = UUID.randomUUID(),
        name = "Code Review",
        description = "Reviews PRs",
        body = "## Body",
        skillRelativePath = "core/code-review",
        resourceRoot = "/tmp/skills/core/code-review",
    )
    val skill2 = Skill(
        metadata = EntityMetadata(),
        namespaceId = UUID.randomUUID(),
        name = "Spec Writing",
        description = "Writes specs",
        body = "## Body",
        skillRelativePath = "product/spec",
        resourceRoot = "/tmp/skills/product/spec",
    )

    "isGranted returns false when skill list is empty" {
        service.isGranted(emptyList()) shouldBe false
    }

    "isGranted returns true when skill list is non-empty" {
        service.isGranted(listOf(skill1)) shouldBe true
        service.isGranted(listOf(skill1, skill2)) shouldBe true
    }

    "grantTools returns two tools (readSkill and readSkillResource)" {
        every { toolContext.agentName } returns "test-agent"

        val tools = service.grantTools(listOf(skill1), toolContext)

        tools shouldHaveSize 2
        tools.map { it.name }.toSet() shouldBe setOf("readSkill", "readSkillResource")
    }

    "grantTools with empty list still returns tools (caller guards with isGranted)" {
        every { toolContext.agentName } returns "test-agent"

        // SkillToolGrantService.grantTools is called only when isGranted=true,
        // but if called with empty list the plugin still returns the tool instances.
        val tools = service.grantTools(emptyList(), toolContext)
        tools shouldHaveSize 2
    }
})
