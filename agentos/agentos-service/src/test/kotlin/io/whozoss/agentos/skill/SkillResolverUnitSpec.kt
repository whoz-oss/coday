package io.whozoss.agentos.skill

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SkillResolverUnitSpec : StringSpec({

    val resolver = SkillResolver()

    fun tempConfigPath(): Path {
        // Models actual Namespace.configPath semantics: <projectRoot>/coday
        // Each call produces a fresh temp directory so cache entries never leak between tests.
        val projectRoot = Files.createTempDirectory("skill-resolver-test")
        val configPath = projectRoot.resolve("coday").createDirectories()
        projectRoot.toFile().deleteOnExit()
        return configPath
    }

    fun createSkill(
        configPath: Path,
        relativeSkillDir: String,
        frontmatterName: String?,
        frontmatterDescription: String?,
        extraFrontmatter: String = "",
        markdownBody: String = "## Guidelines\nDo this and that.",
    ): Path {
        // relativeSkillDir under configPath/skills/
        val dir = configPath.resolve("skills").resolve(relativeSkillDir).createDirectories()
        val skillFile = dir.resolve("SKILL.md")
        val content = buildString {
            appendLine("---")
            if (frontmatterName != null) appendLine("name: $frontmatterName")
            if (frontmatterDescription != null) appendLine("description: $frontmatterDescription")
            if (extraFrontmatter.isNotBlank()) appendLine(extraFrontmatter)
            appendLine("---")
            appendLine()
            appendLine(markdownBody)
        }
        skillFile.writeText(content)
        return skillFile
    }

    "returns empty list when namespaceConfigPath is null" {
        resolver.discoverSkills(null).shouldBeEmpty()
        resolver.buildSkillsBlock(null).shouldBeNull()
    }

    "returns empty list when namespaceConfigPath is blank" {
        resolver.discoverSkills("   ").shouldBeEmpty()
        resolver.buildSkillsBlock("   ").shouldBeNull()
    }

    "returns empty list when configPath/skills directory does not exist" {
        val configPath = tempConfigPath()
        resolver.discoverSkills(configPath.toString()).shouldBeEmpty()
        resolver.buildSkillsBlock(configPath.toString()).shouldBeNull()
    }

    "regression test: standard JSON ObjectMapper fails to parse YAML frontmatter without braces" {
        val standardJsonMapper = ObjectMapper()
        val yamlFrontmatter = "name: Test Skill\ndescription: A test description"
        shouldThrow<JsonParseException> {
            standardJsonMapper.readValue(yamlFrontmatter, Map::class.java)
        }
    }

    "regression test: does not expect doubled coday/coday/skills path" {
        val configPath = tempConfigPath()
        // Create under configPath/skills (coday/skills)
        createSkill(
            configPath = configPath,
            relativeSkillDir = "code-review",
            frontmatterName = "Code Review",
            frontmatterDescription = "Reviews PRs",
        )

        // Must discover from configPath directly (coday/skills), NOT coday/coday/skills
        val skills = resolver.discoverSkills(configPath.toString())
        skills shouldHaveSize 1
        skills.single().relativePath shouldBe "coday/skills/code-review/SKILL.md"
        skills.single().relativePath shouldNotContain "coday/coday"
    }

    // Relative single-segment configPath must still produce the correct relativePath.
    "relative configPath produces correct project-root-relative relativePath" {
        // Use a real temp directory but pass its name (last segment) as a relative path.
        // toAbsolutePath().normalize() inside discoverSkills must recover the full path.
        val configPath = tempConfigPath()
        createSkill(
            configPath = configPath,
            relativeSkillDir = "code-review",
            frontmatterName = "Code Review",
            frontmatterDescription = "Reviews PRs",
        )

        // Pass the absolute path (the only reliable way to get a consistent relative form
        // without changing the working directory, which is not possible in a test).
        // The key invariant: relativePath must be "<configDirName>/skills/.../SKILL.md".
        val skills = resolver.discoverSkills(configPath.toString())
        skills shouldHaveSize 1
        skills.single().relativePath shouldEndWith "skills/code-review/SKILL.md"
        skills.single().relativePath shouldNotContain "//"
    }

    "discovers valid skills under configPath/skills" {
        val configPath = tempConfigPath()
        createSkill(
            configPath = configPath,
            relativeSkillDir = "code-review",
            frontmatterName = "Code Review",
            frontmatterDescription = "Reviews pull requests against standards",
        )
        createSkill(
            configPath = configPath,
            relativeSkillDir = "refactoring",
            frontmatterName = "Refactoring",
            frontmatterDescription = "Suggests safe refactoring steps",
        )

        val skills = resolver.discoverSkills(configPath.toString())

        skills shouldHaveSize 2
        skills[0].name shouldBe "Code Review"
        skills[0].description shouldBe "Reviews pull requests against standards"
        skills[0].relativePath shouldBe "coday/skills/code-review/SKILL.md"

        skills[1].name shouldBe "Refactoring"
        skills[1].description shouldBe "Suggests safe refactoring steps"
        skills[1].relativePath shouldBe "coday/skills/refactoring/SKILL.md"
    }

    "parses quoted values and unknown fields safely in frontmatter" {
        val configPath = tempConfigPath()
        createSkill(
            configPath = configPath,
            relativeSkillDir = "quoted-skill",
            frontmatterName = "\"Double Quoted Skill: Core\"",
            frontmatterDescription = "'Single quoted description with special chars: @#\$'",
            extraFrontmatter = "version: 1.2.0\nauthor: Engineering\nlicense: MIT",
        )

        val skills = resolver.discoverSkills(configPath.toString())

        skills shouldHaveSize 1
        skills.single().name shouldBe "Double Quoted Skill: Core"
        skills.single().description shouldBe "Single quoted description with special chars: @#\$"
    }

    // Folded multiline descriptions must still parse; whitespace collapsing is applied after.
    "parses folded multiline descriptions correctly and collapses whitespace" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/folded-skill").createDirectories()
        val skillFile = dir.resolve("SKILL.md")
        skillFile.writeText(
            """
            ---
            name: Folded Description Skill
            description: >
              This is a folded multiline description
              that spans across multiple lines in YAML
              and should be resolved as a single coherent text.
            ---
            ## Content
            Body here
            """.trimIndent(),
        )

        val skills = resolver.discoverSkills(configPath.toString())

        skills shouldHaveSize 1
        skills.single().name shouldBe "Folded Description Skill"
        // Whitespace (including the newline YAML folding inserts) is collapsed to spaces.
        // The description must contain the words but must not contain literal newlines.
        val desc = skills.single().description
        desc shouldContain "This is a folded multiline description"
        desc shouldContain "spans across multiple lines"
        // After collapsing, no raw newlines remain in the injected value.
        (desc.contains('\n')) shouldBe false
    }

    "discovers nested skills" {
        val configPath = tempConfigPath()
        createSkill(
            configPath = configPath,
            relativeSkillDir = "backend/kotlin",
            frontmatterName = "Kotlin Backend",
            frontmatterDescription = "Kotlin Spring Boot guidelines",
        )

        val skills = resolver.discoverSkills(configPath.toString())

        skills shouldHaveSize 1
        skills.single().name shouldBe "Kotlin Backend"
        skills.single().relativePath shouldBe "coday/skills/backend/kotlin/SKILL.md"
        skills.single().skillRelativePath shouldBe "backend/kotlin"
    }

    "orders skills deterministically by relative path" {
        val configPath = tempConfigPath()
        createSkill(
            configPath = configPath,
            relativeSkillDir = "z-skill",
            frontmatterName = "Z Skill",
            frontmatterDescription = "Z description",
        )
        createSkill(
            configPath = configPath,
            relativeSkillDir = "a-skill",
            frontmatterName = "A Skill",
            frontmatterDescription = "A description",
        )
        createSkill(
            configPath = configPath,
            relativeSkillDir = "m-skill",
            frontmatterName = "M Skill",
            frontmatterDescription = "M description",
        )

        val skills = resolver.discoverSkills(configPath.toString())

        skills.map { it.relativePath } shouldBe listOf(
            "coday/skills/a-skill/SKILL.md",
            "coday/skills/m-skill/SKILL.md",
            "coday/skills/z-skill/SKILL.md",
        )
    }

    "skips malformed frontmatter" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/malformed").createDirectories()
        dir.resolve("SKILL.md").writeText("No frontmatter here\nJust markdown.")

        createSkill(
            configPath = configPath,
            relativeSkillDir = "valid",
            frontmatterName = "Valid Skill",
            frontmatterDescription = "Valid description",
        )

        val skills = resolver.discoverSkills(configPath.toString())

        skills shouldHaveSize 1
        skills.single().name shouldBe "Valid Skill"
    }

    "skips skills with invalid YAML syntax cleanly without full stack traces" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/bad-yaml").createDirectories()
        dir.resolve("SKILL.md").writeText(
            """
            ---
            name: [unclosed list
            description: broken
            ---
            """.trimIndent(),
        )

        createSkill(
            configPath = configPath,
            relativeSkillDir = "valid",
            frontmatterName = "Valid Skill",
            frontmatterDescription = "Valid description",
        )

        val skills = resolver.discoverSkills(configPath.toString())

        skills shouldHaveSize 1
        skills.single().name shouldBe "Valid Skill"
    }

    "skips skills with missing or blank name" {
        val configPath = tempConfigPath()
        createSkill(
            configPath = configPath,
            relativeSkillDir = "missing-name",
            frontmatterName = null,
            frontmatterDescription = "Some description",
        )
        createSkill(
            configPath = configPath,
            relativeSkillDir = "blank-name",
            frontmatterName = "   ",
            frontmatterDescription = "Some description",
        )
        createSkill(
            configPath = configPath,
            relativeSkillDir = "valid",
            frontmatterName = "Valid",
            frontmatterDescription = "Description",
        )

        val skills = resolver.discoverSkills(configPath.toString())

        skills shouldHaveSize 1
        skills.single().name shouldBe "Valid"
    }

    "skips skills with missing or blank description" {
        val configPath = tempConfigPath()
        createSkill(
            configPath = configPath,
            relativeSkillDir = "missing-desc",
            frontmatterName = "Skill 1",
            frontmatterDescription = null,
        )
        createSkill(
            configPath = configPath,
            relativeSkillDir = "blank-desc",
            frontmatterName = "Skill 2",
            frontmatterDescription = "   ",
        )

        val skills = resolver.discoverSkills(configPath.toString())

        skills.shouldBeEmpty()
    }

    "detects duplicate skill names deterministically and keeps the first by path order" {
        val configPath = tempConfigPath()
        // a-skill comes first alphabetically
        createSkill(
            configPath = configPath,
            relativeSkillDir = "a-skill",
            frontmatterName = "DuplicateName",
            frontmatterDescription = "First occurrence",
        )
        // b-skill comes second alphabetically with duplicate name (case-insensitive)
        createSkill(
            configPath = configPath,
            relativeSkillDir = "b-skill",
            frontmatterName = "duplicatename",
            frontmatterDescription = "Second occurrence",
        )

        val skills = resolver.discoverSkills(configPath.toString())

        skills shouldHaveSize 1
        skills.single().description shouldBe "First occurrence"
        skills.single().relativePath shouldBe "coday/skills/a-skill/SKILL.md"
    }

    "safely handles symlink escaping the skills root boundary" {
        val configPath = tempConfigPath()
        val outsideDir = Files.createTempDirectory("outside-skills")
        outsideDir.toFile().deleteOnExit()
        val outsideSkill = outsideDir.resolve("SKILL.md")
        outsideSkill.writeText(
            """
            ---
            name: Escaped Skill
            description: Escaped description
            ---
            """.trimIndent(),
        )

        val skillsDir = configPath.resolve("skills").createDirectories()
        val symlink = skillsDir.resolve("symlink-skill")

        val symlinkCreated = runCatching {
            Files.createSymbolicLink(symlink, outsideDir)
            true
        }.getOrDefault(false)

        if (symlinkCreated) {
            createSkill(
                configPath = configPath,
                relativeSkillDir = "inside",
                frontmatterName = "Inside Skill",
                frontmatterDescription = "Inside description",
            )

            val skills = resolver.discoverSkills(configPath.toString())

            skills shouldHaveSize 1
            skills.single().name shouldBe "Inside Skill"
        }
    }

    // Files exceeding MAX_SKILL_FILE_BYTES must be skipped, not read.
    "oversized SKILL.md is skipped and the rest of the catalog is unaffected" {
        val configPath = tempConfigPath()
        // Create an oversized file (just above the 256 KiB threshold).
        val oversizedDir = configPath.resolve("skills/oversized").createDirectories()
        val oversizedFile = oversizedDir.resolve("SKILL.md")
        // Write a valid-looking frontmatter header followed by enough padding to exceed the limit.
        val padding = "x".repeat(SkillResolver.MAX_SKILL_FILE_BYTES.toInt() + 1)
        oversizedFile.writeText("---\nname: Big Skill\ndescription: Too big\n---\n$padding")

        createSkill(
            configPath = configPath,
            relativeSkillDir = "normal",
            frontmatterName = "Normal Skill",
            frontmatterDescription = "Fits fine",
        )

        val skills = resolver.discoverSkills(configPath.toString())

        // Oversized file must be skipped; the normal one must still be discovered.
        skills shouldHaveSize 1
        skills.single().name shouldBe "Normal Skill"
    }

    // Name and description exceeding the character caps must be truncated with ellipsis.
    "name and description exceeding char caps are truncated with ellipsis" {
        val configPath = tempConfigPath()
        val longName = "N".repeat(SkillResolver.MAX_SKILL_NAME_CHARS + 50)
        val longDesc = "D".repeat(SkillResolver.MAX_SKILL_DESCRIPTION_CHARS + 100)
        createSkill(
            configPath = configPath,
            relativeSkillDir = "long-fields",
            frontmatterName = longName,
            frontmatterDescription = longDesc,
        )

        val skills = resolver.discoverSkills(configPath.toString())

        skills shouldHaveSize 1
        val skill = skills.single()
        skill.name.length shouldBe SkillResolver.MAX_SKILL_NAME_CHARS + 1 // +1 for the ellipsis char
        skill.name shouldEndWith "\u2026"
        skill.description.length shouldBe SkillResolver.MAX_SKILL_DESCRIPTION_CHARS + 1
        skill.description shouldEndWith "\u2026"
    }

    // Whitespace collapsing in name and description.
    "whitespace runs in name and description are collapsed to single spaces" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/whitespace-skill").createDirectories()
        val skillFile = dir.resolve("SKILL.md")
        // Use a literal block scalar for description with embedded newlines and tabs.
        skillFile.writeText(
            "---\n" +
                "name: \"  Spaced   Out  Name  \"\n" +
                "description: |\n" +
                "  Line one\n" +
                "  Line two\n" +
                "  Line three\n" +
                "---\n## Body\n",
        )

        val skills = resolver.discoverSkills(configPath.toString())

        skills shouldHaveSize 1
        val skill = skills.single()
        // Multiple spaces in name must be collapsed.
        skill.name shouldBe "Spaced Out Name"
        // Newlines from the block scalar must be collapsed to single spaces.
        skill.description shouldBe "Line one Line two Line three"
        (skill.description.contains('\n')) shouldBe false
    }

    // A second discoverSkills call within the TTL must not observe a newly written skill.
    "second discoverSkills call within TTL returns cached result" {
        val configPath = tempConfigPath()
        createSkill(
            configPath = configPath,
            relativeSkillDir = "original",
            frontmatterName = "Original Skill",
            frontmatterDescription = "First skill",
        )

        // Prime the cache.
        val first = resolver.discoverSkills(configPath.toString())
        first shouldHaveSize 1
        first.single().name shouldBe "Original Skill"

        // Write a second skill file while the cache entry is still fresh.
        createSkill(
            configPath = configPath,
            relativeSkillDir = "new-skill",
            frontmatterName = "New Skill",
            frontmatterDescription = "Added after cache prime",
        )

        // Second call within TTL must return the cached list — new skill is not visible.
        val second = resolver.discoverSkills(configPath.toString())
        second shouldHaveSize 1
        second.single().name shouldBe "Original Skill"
    }

    // Root-level SKILL.md (directly under skills/) has skillRelativePath == "".
    "root-level SKILL.md is selectable by name and by SKILL.md, not by empty-prefix selector" {
        val configPath = tempConfigPath()
        // Place SKILL.md directly under skills/ (no subdirectory).
        val skillsDir = configPath.resolve("skills").createDirectories()
        skillsDir.resolve("SKILL.md").writeText(
            "---\nname: Root Skill\ndescription: Lives at the root of skills\n---\n## Body\n",
        )
        // Also add a normal nested skill to verify the prefix guard.
        createSkill(
            configPath = configPath,
            relativeSkillDir = "nested/child",
            frontmatterName = "Nested Skill",
            frontmatterDescription = "Lives in a subdirectory",
        )

        val skills = resolver.discoverSkills(configPath.toString())
        skills shouldHaveSize 2

        val rootSkill = skills.first { it.name == "Root Skill" }
        rootSkill.skillRelativePath shouldBe ""

        // Must be selectable by name.
        resolver.filterSkills(skills, listOf("Root Skill")) shouldBe listOf(rootSkill)

        // Must be selectable by the bare "SKILL.md" filename selector.
        resolver.filterSkills(skills, listOf("SKILL.md")) shouldBe listOf(rootSkill)

        // A "/**" or "/*" selector with empty prefix must NOT sweep in every skill.
        // (An empty prefix would match everything, which is wrong.)
        // Use a selector that would produce an empty prefix after stripping the glob suffix,
        // e.g. "/**" — this must not match anything (the guard rejects empty prefix).
        resolver.filterSkills(skills, listOf("/**")).shouldBeEmpty()
        resolver.filterSkills(skills, listOf("/*")).shouldBeEmpty()
    }

    "filterSkills handles null, empty, wildcard, exact, folder selectors and deduplication" {
        val skill1 = Skill("spec-writing", "Writes specs", "coday/skills/product/spec-writing/SKILL.md", "product/spec-writing")
        val skill2 = Skill("jira-writing", "Writes tickets", "coday/skills/product/jira-writing/SKILL.md", "product/jira-writing")
        val skill3 = Skill("branch-creation", "Creates branches", "coday/skills/core/branch-creation/SKILL.md", "core/branch-creation")
        val skill4 = Skill("adversarial-review", "Reviews diffs", "coday/skills/review/adversarial-review/SKILL.md", "review/adversarial-review")
        val all = listOf(skill3, skill1, skill2, skill4) // discovery order: core, product, review

        // 1. null selectors => all skills (Claude-compatible default)
        resolver.filterSkills(all, null) shouldBe all

        // 2. empty list [] => no skills
        resolver.filterSkills(all, emptyList()).shouldBeEmpty()

        // 3. wildcard * => all skills
        resolver.filterSkills(all, listOf("*")) shouldBe all

        // 4. folder prefix selectors (core/**, core/*)
        resolver.filterSkills(all, listOf("core/**")) shouldBe listOf(skill3)
        resolver.filterSkills(all, listOf("core/*")) shouldBe listOf(skill3)

        // 5. exact skill selectors by path and by name
        resolver.filterSkills(all, listOf("product/spec-writing")) shouldBe listOf(skill1)
        resolver.filterSkills(all, listOf("product/spec-writing/SKILL.md")) shouldBe listOf(skill1)
        resolver.filterSkills(all, listOf("coday/skills/product/spec-writing/SKILL.md")) shouldBe listOf(skill1)
        resolver.filterSkills(all, listOf("spec-writing")) shouldBe listOf(skill1)

        // 6. combined selectors with overlap deduplication preserving deterministic order
        val combined = resolver.filterSkills(all, listOf("core/**", "product/**", "review/adversarial-review", "core/branch-creation"))
        combined shouldBe listOf(skill3, skill1, skill2, skill4)

        // 7. unknown selectors warn and are ignored
        val withUnknown = resolver.filterSkills(all, listOf("core/**", "nonexistent/**"))
        withUnknown shouldBe listOf(skill3)
    }

    "buildSkillsBlock formats catalog and activation protocol" {
        val configPath = tempConfigPath()
        createSkill(
            configPath = configPath,
            relativeSkillDir = "core/branch-creation",
            frontmatterName = "branch-creation",
            frontmatterDescription = "Creates git branch",
        )
        createSkill(
            configPath = configPath,
            relativeSkillDir = "backend/controller-testing",
            frontmatterName = "controller-testing",
            frontmatterDescription = "Tests backend controllers",
        )

        // Unfiltered (null selectors)
        val fullBlock = resolver.buildSkillsBlock(configPath.toString(), null)
        fullBlock.shouldNotBeNull()
        fullBlock shouldContain "## Available Skills"
        fullBlock shouldContain "- **controller-testing** (`coday/skills/backend/controller-testing/SKILL.md`): Tests backend controllers"
        fullBlock shouldContain "- **branch-creation** (`coday/skills/core/branch-creation/SKILL.md`): Creates git branch"
        fullBlock shouldContain "### Skill Activation Protocol"

        // Filtered with skillSelectors (only core/**)
        val filteredBlock = resolver.buildSkillsBlock(configPath.toString(), listOf("core/**"))
        filteredBlock.shouldNotBeNull()
        filteredBlock shouldContain "branch-creation"
        filteredBlock shouldNotContain "controller-testing"

        // Opt-out with empty skillSelectors ([])
        val emptyBlock = resolver.buildSkillsBlock(configPath.toString(), emptyList())
        emptyBlock.shouldBeNull()
    }
})
