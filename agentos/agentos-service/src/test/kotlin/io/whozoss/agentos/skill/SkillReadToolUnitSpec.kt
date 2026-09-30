package io.whozoss.agentos.skill

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.util.UUID

class SkillReadToolUnitSpec : StringSpec({

    val toolContext = mockk<ToolContext>(relaxed = true)

    fun skill(
        name: String = "Code Review",
        body: String = "## Guidelines\nDo this.",
        resources: Map<String, String> = emptyMap(),
    ) = Skill(
        metadata = EntityMetadata(),
        namespaceId = UUID.randomUUID(),
        name = name,
        description = "Reviews code",
        body = body,
        resources = resources,
    )

    // -------------------------------------------------------------------------
    // SkillReadTool
    // -------------------------------------------------------------------------

    "readSkill happy path returns skill body" {
        val s = skill(body = "## Guidelines\nStep 1. Step 2.")
        val tool = SkillReadTool(listOf(s))

        val result = kotlinx.coroutines.runBlocking {
            tool.execute(SkillReadTool.Input("Code Review"), toolContext)
        }

        result shouldBe ToolExecutionResult.success("## Guidelines\nStep 1. Step 2.")
    }

    "readSkill case-insensitive name match" {
        val s = skill(name = "Code Review", body = "body")
        val tool = SkillReadTool(listOf(s))

        val result = kotlinx.coroutines.runBlocking {
            tool.execute(SkillReadTool.Input("CODE REVIEW"), toolContext)
        }

        result shouldBe ToolExecutionResult.success("body")
    }

    "readSkill unknown name returns error" {
        val tool = SkillReadTool(listOf(skill()))

        val result = kotlinx.coroutines.runBlocking {
            tool.execute(SkillReadTool.Input("nonexistent"), toolContext)
        }

        result.success shouldBe false
        result.output shouldContain "not found"
    }

    "readSkill null input returns error" {
        val tool = SkillReadTool(emptyList())

        val result = kotlinx.coroutines.runBlocking {
            tool.execute(null, toolContext)
        }

        result.success shouldBe false
    }

    // -------------------------------------------------------------------------
    // SkillReadResourceTool
    // -------------------------------------------------------------------------

    "readSkillResource happy path returns resource content" {
        val s = skill(resources = mapOf("template.md" to "# Template content"))
        val tool = SkillReadResourceTool(listOf(s))

        val result = kotlinx.coroutines.runBlocking {
            tool.execute(SkillReadResourceTool.Input("Code Review", "template.md"), toolContext)
        }

        result shouldBe ToolExecutionResult.success("# Template content")
    }

    "readSkillResource unknown skill returns error" {
        val tool = SkillReadResourceTool(listOf(skill()))

        val result = kotlinx.coroutines.runBlocking {
            tool.execute(SkillReadResourceTool.Input("nonexistent", "file.md"), toolContext)
        }

        result.success shouldBe false
        result.output shouldContain "not found"
    }

    "readSkillResource non-existent resource in skill returns error" {
        val s = skill(resources = mapOf("other.md" to "content"))
        val tool = SkillReadResourceTool(listOf(s))

        val result = kotlinx.coroutines.runBlocking {
            tool.execute(SkillReadResourceTool.Input("Code Review", "does-not-exist.md"), toolContext)
        }

        result.success shouldBe false
        result.output shouldContain "not found"
    }

    "readSkillResource sensitive file is rejected" {
        val s = skill(resources = mapOf(".env" to "SECRET=abc"))
        val tool = SkillReadResourceTool(listOf(s))

        val result = kotlinx.coroutines.runBlocking {
            tool.execute(SkillReadResourceTool.Input("Code Review", ".env"), toolContext)
        }

        result.success shouldBe false
        result.output shouldContain "sensitive"
    }

    // -------------------------------------------------------------------------
    // isSensitiveFile helper
    // -------------------------------------------------------------------------

    "isSensitiveFile detects known sensitive patterns" {
        SkillReadResourceTool.isSensitiveFile(".env") shouldBe true
        SkillReadResourceTool.isSensitiveFile(".env.local") shouldBe true
        SkillReadResourceTool.isSensitiveFile("credentials.json") shouldBe true
        SkillReadResourceTool.isSensitiveFile("id_rsa") shouldBe true
        SkillReadResourceTool.isSensitiveFile("my.key") shouldBe true
        SkillReadResourceTool.isSensitiveFile("cert.pem") shouldBe true
    }

    "isSensitiveFile allows non-sensitive files" {
        SkillReadResourceTool.isSensitiveFile("template.md") shouldBe false
        SkillReadResourceTool.isSensitiveFile("README.md") shouldBe false
        SkillReadResourceTool.isSensitiveFile("config.yaml") shouldBe false
    }
})
