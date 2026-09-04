package io.whozoss.agentos.skill

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain as shouldNotContainElement
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldNotEndWith
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class FilesystemSkillRepositoryUnitSpec : StringSpec({

    val yamlMapper: ObjectMapper =
        ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())

    fun repo(ttl: Duration = Duration.ofMinutes(5)) = FilesystemSkillRepository(yamlMapper, ttl)

    fun tempConfigPath(): Path {
        val root = Files.createTempDirectory("skill-repo-test")
        val configPath = root.resolve("coday").createDirectories()
        root.toFile().deleteOnExit()
        return configPath
    }

    fun createSkill(
        configPath: Path,
        relativeSkillDir: String,
        name: String?,
        description: String?,
        extraFrontmatter: String = "",
        body: String = "## Guidelines\nDo this.",
    ): Path {
        val dir = configPath.resolve("skills").resolve(relativeSkillDir).createDirectories()
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

    "returns empty list when skills directory does not exist" {
        val configPath = tempConfigPath()
        repo().findAll(configPath.toString()).shouldBeEmpty()
    }

    "discovers valid skills and sets resourceRoot" {
        val configPath = tempConfigPath()
        createSkill(configPath, "code-review", "Code Review", "Reviews PRs")

        val skills = repo().findAll(configPath.toString())

        skills shouldHaveSize 1
        val skill = skills.single()
        skill.name shouldBe "Code Review"
        skill.description shouldBe "Reviews PRs"
        skill.skillRelativePath shouldBe "code-review"
        skill.resourceRoot shouldEndWith "skills/code-review"
    }

    "body contains markdown after frontmatter" {
        val configPath = tempConfigPath()
        createSkill(configPath, "spec", "Spec", "Writes specs", body = "## Instructions\nWrite specs carefully.")

        val skills = repo().findAll(configPath.toString())

        skills.single().body shouldBe "## Instructions\nWrite specs carefully.\n"
    }

    "body-parsing: single blank separator line after frontmatter is removed" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/sep").createDirectories()
        dir.resolve("SKILL.md").writeText("---\nname: Sep\ndescription: Sep desc\n---\n\nBody line one")

        val skills = repo().findAll(configPath.toString())

        skills.single().body shouldBe "Body line one"
    }

    "body-parsing: subsequent leading blank lines beyond the single separator are preserved" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/leading-blanks").createDirectories()
        dir.resolve("SKILL.md").writeText("---\nname: Blanks\ndescription: Blanks desc\n---\n\n\nBody starts here")

        val skills = repo().findAll(configPath.toString())

        skills.single().body shouldBe "\nBody starts here"
    }

    "body-parsing: internal blank lines within the body are preserved" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/internal-blanks").createDirectories()
        dir.resolve("SKILL.md").writeText("---\nname: Internal\ndescription: Internal desc\n---\n\nLine one\n\nLine two")

        val skills = repo().findAll(configPath.toString())

        skills.single().body shouldBe "Line one\n\nLine two"
    }

    "body-parsing: no synthetic trailing newline is added when the file has none" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/no-trailing").createDirectories()
        dir.resolve("SKILL.md").writeText("---\nname: NoTrail\ndescription: NoTrail desc\n---\n\nLast line, no newline")

        val skills = repo().findAll(configPath.toString())

        skills.single().body shouldBe "Last line, no newline"
        skills.single().body shouldNotEndWith "\n"
    }

    "body-parsing: existing trailing newline in the file is preserved" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/trailing").createDirectories()
        dir.resolve("SKILL.md").writeText("---\nname: Trail\ndescription: Trail desc\n---\n\nLast line\n")

        val skills = repo().findAll(configPath.toString())

        skills.single().body shouldBe "Last line\n"
    }

    "body-parsing: CRLF input is normalized to LF" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/crlf").createDirectories()
        dir.resolve("SKILL.md").writeText("---\r\nname: Crlf\r\ndescription: Crlf desc\r\n---\r\n\r\nLine one\r\nLine two\r\n")

        val skills = repo().findAll(configPath.toString())

        val skill = skills.single()
        skill.name shouldBe "Crlf"
        skill.body shouldBe "Line one\nLine two\n"
    }

    "body is not truncated by name/description char caps" {
        val configPath = tempConfigPath()
        val longBody = "x".repeat(FilesystemSkillRepository.MAX_SKILL_NAME_CHARS + 200)
        createSkill(configPath, "long-body", "Long", "Desc", body = longBody)

        val skills = repo().findAll(configPath.toString())

        skills.single().body.trim() shouldBe longBody
    }

    "orders skills by skillRelativePath" {
        val configPath = tempConfigPath()
        createSkill(configPath, "z-skill", "Z", "Z desc")
        createSkill(configPath, "a-skill", "A", "A desc")
        createSkill(configPath, "m-skill", "M", "M desc")

        val skills = repo().findAll(configPath.toString())

        skills.map { it.skillRelativePath } shouldBe listOf("a-skill", "m-skill", "z-skill")
    }

    "skips malformed frontmatter" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/malformed").createDirectories()
        dir.resolve("SKILL.md").writeText("No frontmatter here.")
        createSkill(configPath, "valid", "Valid", "Valid desc")

        val skills = repo().findAll(configPath.toString())

        skills shouldHaveSize 1
        skills.single().name shouldBe "Valid"
    }

    "skips skills with blank name or description" {
        val configPath = tempConfigPath()
        createSkill(configPath, "no-name", null, "Desc")
        createSkill(configPath, "blank-name", "   ", "Desc")
        createSkill(configPath, "no-desc", "Skill", null)
        createSkill(configPath, "valid", "Valid", "Valid desc")

        val skills = repo().findAll(configPath.toString())

        skills shouldHaveSize 1
        skills.single().name shouldBe "Valid"
    }

    "deduplicates by name case-insensitively, first-by-path wins" {
        val configPath = tempConfigPath()
        createSkill(configPath, "a-skill", "DuplicateName", "First")
        createSkill(configPath, "b-skill", "duplicatename", "Second")

        val skills = repo().findAll(configPath.toString())

        skills shouldHaveSize 1
        skills.single().description shouldBe "First"
    }

    "truncates name and description exceeding char caps with ellipsis" {
        val configPath = tempConfigPath()
        val longName = "N".repeat(FilesystemSkillRepository.MAX_SKILL_NAME_CHARS + 50)
        val longDesc = "D".repeat(FilesystemSkillRepository.MAX_SKILL_DESCRIPTION_CHARS + 100)
        createSkill(configPath, "long", longName, longDesc)

        val skills = repo().findAll(configPath.toString())

        val skill = skills.single()
        skill.name.length shouldBe FilesystemSkillRepository.MAX_SKILL_NAME_CHARS + 1
        skill.name shouldEndWith "\u2026"
        skill.description.length shouldBe FilesystemSkillRepository.MAX_SKILL_DESCRIPTION_CHARS + 1
        skill.description shouldEndWith "\u2026"
    }

    "collapses whitespace in name and description" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/ws").createDirectories()
        dir.resolve("SKILL.md").writeText(
            "---\n" +
                "name: \"  Spaced   Out  \"\n" +
                "description: |\n" +
                "  Line one\n" +
                "  Line two\n" +
                "---\n## Body\n",
        )

        val skills = repo().findAll(configPath.toString())

        skills.single().name shouldBe "Spaced Out"
        skills.single().description shouldBe "Line one Line two"
    }

    "skips oversized SKILL.md" {
        val configPath = tempConfigPath()
        val oversizedDir = configPath.resolve("skills/big").createDirectories()
        val padding = "x".repeat(FilesystemSkillRepository.MAX_SKILL_FILE_BYTES.toInt() + 1)
        oversizedDir.resolve("SKILL.md").writeText("---\nname: Big\ndescription: Too big\n---\n$padding")
        createSkill(configPath, "normal", "Normal", "Fits fine")

        val skills = repo().findAll(configPath.toString())

        skills shouldHaveSize 1
        skills.single().name shouldBe "Normal"
    }

    "skips invalid YAML syntax without propagating exception" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/bad-yaml").createDirectories()
        dir.resolve("SKILL.md").writeText("---\nname: [unclosed\ndescription: broken\n---\n")
        createSkill(configPath, "valid", "Valid", "Valid desc")

        val skills = repo().findAll(configPath.toString())

        skills shouldHaveSize 1
    }

    "skips SKILL.md exceeding MAX_WALK_DEPTH" {
        val configPath = tempConfigPath()
        val deepPath = (1..(FilesystemSkillRepository.MAX_WALK_DEPTH)).joinToString("/") { "d$it" }
        val deepDir = configPath.resolve("skills/$deepPath").createDirectories()
        deepDir.resolve("SKILL.md").writeText("---\nname: Deep\ndescription: Too deep\n---\n## Body\n")
        createSkill(configPath, "shallow", "Shallow", "Shallow desc")

        val skills = repo().findAll(configPath.toString())

        skills shouldHaveSize 1
        skills.single().name shouldBe "Shallow"
    }

    "ignores non-SKILL.md files" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/myskill").createDirectories()
        dir.resolve("SKILL.md").writeText("---\nname: Real Skill\ndescription: Real desc\n---\n## Body\n")
        dir.resolve("notes.yaml").writeText("name: Should Not Appear")
        dir.resolve("README.md").writeText("# ignored")

        val skills = repo().findAll(configPath.toString())

        skills shouldHaveSize 1
        skills.single().name shouldBe "Real Skill"
    }

    "symlink escaping skills root is rejected" {
        val configPath = tempConfigPath()
        val outsideDir = Files.createTempDirectory("outside")
        outsideDir.toFile().deleteOnExit()
        outsideDir.resolve("SKILL.md").writeText("---\nname: Escaped\ndescription: Escaped desc\n---\n## Body\n")

        val skillsDir = configPath.resolve("skills").createDirectories()
        val symlink = skillsDir.resolve("symlink-skill")
        val symlinkCreated = runCatching { Files.createSymbolicLink(symlink, outsideDir); true }.getOrDefault(false)

        if (symlinkCreated) {
            createSkill(configPath, "inside", "Inside", "Inside desc")
            val skills = repo().findAll(configPath.toString())
            skills shouldHaveSize 1
            skills.single().name shouldBe "Inside"
        }
    }

    "symlink pointing directly at a file escaping skills root is rejected" {
        val configPath = tempConfigPath()
        val outsideFile = Files.createTempFile("outside-skill", ".md")
        outsideFile.toFile().deleteOnExit()
        outsideFile.writeText("---\nname: EscapedFile\ndescription: Escaped file desc\n---\n## Body\n")

        val skillsDir = configPath.resolve("skills").resolve("symlinked-file-skill").createDirectories()
        val symlink = skillsDir.resolve("SKILL.md")
        val symlinkCreated = runCatching { Files.createSymbolicLink(symlink, outsideFile); true }.getOrDefault(false)

        if (symlinkCreated) {
            createSkill(configPath, "inside", "Inside", "Inside desc")
            val skills = repo().findAll(configPath.toString())
            skills shouldHaveSize 1
            skills.single().name shouldBe "Inside"
        }
    }

    "second findAll call within TTL returns cached result" {
        val configPath = tempConfigPath()
        createSkill(configPath, "original", "Original", "First skill")

        val r = repo()
        val first = r.findAll(configPath.toString())
        first shouldHaveSize 1

        createSkill(configPath, "new-skill", "New Skill", "Added after cache")

        val second = r.findAll(configPath.toString())
        second shouldHaveSize 1
        second.single().name shouldBe "Original"
    }

    "nested skills have correct skillRelativePath" {
        val configPath = tempConfigPath()
        createSkill(configPath, "backend/kotlin", "Kotlin Backend", "Kotlin guidelines")

        val skills = repo().findAll(configPath.toString())

        skills.single().skillRelativePath shouldBe "backend/kotlin"
    }

    "catalog not emitting project-root-relative paths" {
        val configPath = tempConfigPath()
        createSkill(configPath, "product/spec", "Spec Writing", "Writes specs")

        val skills = repo().findAll(configPath.toString())

        val skill = skills.single()
        skill.skillRelativePath shouldNotContain "coday"
        skill.skillRelativePath shouldBe "product/spec"
    }

    "caps unique discovered skills at MAX_SKILL_COUNT, first-by-path wins, duplicates never consume the count" {
        val configPath = tempConfigPath()
        val extra = 5
        val total = FilesystemSkillRepository.MAX_SKILL_COUNT + extra
        for (i in 0 until total) {
            val id = i.toString().padStart(4, '0')
            createSkill(configPath, "s$id", "Skill$id", "Desc $id")
        }
        createSkill(configPath, "a-dup-1", "Skill0000", "Earlier path for Skill0000")
        createSkill(configPath, "a-dup-2", "Skill0001", "Earlier path for Skill0001")

        val skills = repo().findAll(configPath.toString())

        skills shouldHaveSize FilesystemSkillRepository.MAX_SKILL_COUNT
        skills.first().name shouldBe "Skill0000"
        skills.first().description shouldBe "Earlier path for Skill0000"
        val includedNames = skills.map { it.name }.toSet()
        val lastGeneratedId = (total - 1).toString().padStart(4, '0')
        includedNames shouldNotContainElement "Skill$lastGeneratedId"
    }
})
