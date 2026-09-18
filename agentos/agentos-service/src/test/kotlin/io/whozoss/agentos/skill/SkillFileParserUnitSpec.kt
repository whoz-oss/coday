package io.whozoss.agentos.skill

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SkillFileParserUnitSpec : StringSpec({

    val yamlMapper: ObjectMapper =
        ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())
    val parser = SkillFileParser(yamlMapper)

    fun tempConfigPath(): Path {
        val root = Files.createTempDirectory("skill-parser-test")
        val skillsRoot = root.resolve("skills").createDirectories()
        root.toFile().deleteOnExit()
        return skillsRoot
    }

    fun createSkill(
        skillsRoot: Path,
        skillDirName: String,
        name: String?,
        description: String?,
        extraFrontmatter: String = "",
        body: String = "## Guidelines\nDo this.",
    ): Path {
        val dir = skillsRoot.resolve(skillDirName).createDirectories()
        val file = dir.resolve("SKILL.md")
        val content = buildString {
            appendLine("---")
            if (name != null) appendLine("name: $name")
            if (description != null) appendLine("description: $description")
            if (extraFrontmatter.isNotBlank()) appendLine(extraFrontmatter)
            appendLine("---")
            appendLine()
            appendLine(body)
        }
        file.writeText(content)
        return file
    }

    "parses valid SKILL.md file and extracts frontmatter + body" {
        val skillsRoot = tempConfigPath()
        val file = createSkill(skillsRoot, "code-review", "Code Review", "Reviews code")

        val skill = parser.parseSkillFile(skillsRoot, file)

        skill.shouldNotBeNull()
        skill.name shouldBe "Code Review"
        skill.description shouldBe "Reviews code"
        skill.body.trim() shouldBe "## Guidelines\nDo this."
    }

    "skips file with blank name or description" {
        val skillsRoot = tempConfigPath()
        val blankNameFile = createSkill(skillsRoot, "blank-name", "", "Desc")
        val blankDescFile = createSkill(skillsRoot, "blank-desc", "Name", "")

        parser.parseSkillFile(skillsRoot, blankNameFile).shouldBeNull()
        parser.parseSkillFile(skillsRoot, blankDescFile).shouldBeNull()
    }

    "skips file with invalid skill name characters" {
        val skillsRoot = tempConfigPath()
        val invalidCharFile = createSkill(skillsRoot, "invalid-name", "Code*Review", "Desc")
        val slashFile = createSkill(skillsRoot, "slash-name", "code/review", "Desc")

        parser.parseSkillFile(skillsRoot, invalidCharFile).shouldBeNull()
        parser.parseSkillFile(skillsRoot, slashFile).shouldBeNull()
    }

    "validates name using isValidSkillName regex" {
        SkillFileParser.isValidSkillName("code-review") shouldBe true
        SkillFileParser.isValidSkillName("Code Review 2.0") shouldBe true
        SkillFileParser.isValidSkillName("skill_name.v1") shouldBe true
        SkillFileParser.isValidSkillName("invalid/name") shouldBe false
        SkillFileParser.isValidSkillName("invalid*name") shouldBe false
        SkillFileParser.isValidSkillName("") shouldBe false
        SkillFileParser.isValidSkillName("a".repeat(101)) shouldBe false
    }

    "discovers auxiliary resources with normalized relative paths" {
        val skillsRoot = tempConfigPath()
        val file = createSkill(skillsRoot, "with-resources", "With Resources", "Desc")
        val skillDir = file.parent
        skillDir.resolve("ref").createDirectories().resolve("doc.md").writeText("Doc content")

        val skill = parser.parseSkillFile(skillsRoot, file)

        skill.shouldNotBeNull()
        skill.resources["ref/doc.md"] shouldBe "Doc content"
    }
})
