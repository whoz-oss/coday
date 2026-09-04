package io.whozoss.agentos.skill

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SkillReadToolUnitSpec : StringSpec({

    val toolContext = mockk<ToolContext>(relaxed = true)

    fun tempSkillDir(): java.nio.file.Path {
        val dir = Files.createTempDirectory("skill-tool-test")
        dir.toFile().deleteOnExit()
        return dir
    }

    fun skill(
        name: String = "Code Review",
        body: String = "## Guidelines\nDo this.",
        resourceRoot: String? = null,
    ) = Skill(name, "Reviews code", body, "core/${name.lowercase().replace(' ', '-')}", resourceRoot)

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

    "readSkillResource happy path returns file content" {
        val dir = tempSkillDir()
        dir.resolve("template.md").writeText("# Template content")
        val s = skill(resourceRoot = dir.toString())
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

    "readSkillResource null resourceRoot returns error" {
        val s = Skill("No Root", "desc", "body", "core/no-root", resourceRoot = null)
        val tool = SkillReadResourceTool(listOf(s))

        val result = kotlinx.coroutines.runBlocking {
            tool.execute(SkillReadResourceTool.Input("No Root", "file.md"), toolContext)
        }

        result.success shouldBe false
        result.output shouldContain "no bundled resources"
    }

    "readSkillResource path traversal is rejected" {
        val dir = tempSkillDir()
        val s = skill(resourceRoot = dir.toString())
        val tool = SkillReadResourceTool(listOf(s))

        val result = kotlinx.coroutines.runBlocking {
            tool.execute(SkillReadResourceTool.Input("Code Review", "../../etc/passwd"), toolContext)
        }

        result.success shouldBe false
    }

    "readSkillResource sensitive file is rejected" {
        val dir = tempSkillDir()
        dir.resolve(".env").writeText("SECRET=abc")
        val s = skill(resourceRoot = dir.toString())
        val tool = SkillReadResourceTool(listOf(s))

        val result = kotlinx.coroutines.runBlocking {
            tool.execute(SkillReadResourceTool.Input("Code Review", ".env"), toolContext)
        }

        result.success shouldBe false
        result.output shouldContain "sensitive"
    }

    "readSkillResource oversized file is rejected" {
        val dir = tempSkillDir()
        val bigFile = dir.resolve("big.txt")
        bigFile.writeText("x".repeat((SkillReadResourceTool.Companion.MAX_RESOURCE_BYTES.toInt()) + 1))
        // MAX_RESOURCE_BYTES is internal; we write just over 1 MiB.
        val s = skill(resourceRoot = dir.toString())
        val tool = SkillReadResourceTool(listOf(s))

        val result = kotlinx.coroutines.runBlocking {
            tool.execute(SkillReadResourceTool.Input("Code Review", "big.txt"), toolContext)
        }

        result.success shouldBe false
        result.output shouldContain "too large"
    }

    "readSkillResource non-existent file returns error" {
        val dir = tempSkillDir()
        val s = skill(resourceRoot = dir.toString())
        val tool = SkillReadResourceTool(listOf(s))

        val result = kotlinx.coroutines.runBlocking {
            tool.execute(SkillReadResourceTool.Input("Code Review", "does-not-exist.md"), toolContext)
        }

        result.success shouldBe false
        result.output shouldContain "not found"
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
