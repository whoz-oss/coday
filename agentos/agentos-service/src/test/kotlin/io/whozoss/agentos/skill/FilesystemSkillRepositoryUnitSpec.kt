package io.whozoss.agentos.skill

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain as shouldNotContainElement
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldNotEndWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class FilesystemSkillRepositoryUnitSpec : StringSpec({

    val yamlMapper: ObjectMapper =
        ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())

    val namespaceId: UUID = UUID.randomUUID()

    fun buildRepo(
        delegate: SkillRepository = mockk(),
        namespaceRepository: NamespaceRepository = mockk(),
        ttl: Duration = Duration.ofMinutes(5),
    ) = FilesystemSkillRepository(
        delegate = delegate,
        namespaceRepository = namespaceRepository,
        yamlMapper = yamlMapper,
        ttl = ttl,
    )

    fun nsRepoWith(
        nsId: UUID,
        configPath: String?,
    ): NamespaceRepository =
        mockk<NamespaceRepository>().also {
            every { it.findByIds(listOf(nsId)) } returns
                listOf(
                    Namespace(
                        metadata = EntityMetadata(id = nsId),
                        name = "ns",
                        configPath = configPath,
                    ),
                )
        }

    fun persistedSkill(
        nsId: UUID?,
        name: String,
        description: String = "Persisted desc",
        body: String = "Persisted body",
    ) = Skill(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        namespaceId = nsId,
        name = name,
        description = description,
        body = body,
    )

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

    // -------------------------------------------------------------------------
    // No configPath / missing namespace — pure delegation
    // -------------------------------------------------------------------------

    "findByNamespaceId delegates to underlying repository when namespace has no configPath" {
        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath = null)
        val persisted = listOf(persistedSkill(namespaceId, "Alpha"))

        every { delegate.findByNamespaceId(namespaceId) } returns persisted

        val result = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        result shouldBe persisted
        verify(exactly = 1) { delegate.findByNamespaceId(namespaceId) }
    }

    "findByNamespaceId delegates to underlying repository when namespace is not found" {
        val delegate = mockk<SkillRepository>()
        val nsRepo = mockk<NamespaceRepository>()
        val persisted = listOf(persistedSkill(namespaceId, "Alpha"))

        every { delegate.findByNamespaceId(namespaceId) } returns persisted
        every { nsRepo.findByIds(listOf(namespaceId)) } returns emptyList()

        val result = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        result shouldBe persisted
    }

    "findByParent delegates to findByNamespaceId" {
        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath = null)
        val persisted = listOf(persistedSkill(namespaceId, "Alpha"))

        every { delegate.findByNamespaceId(namespaceId) } returns persisted

        val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

        result shouldBe persisted
        verify(exactly = 1) { delegate.findByNamespaceId(namespaceId) }
    }

    // -------------------------------------------------------------------------
    // configPath present — filesystem augmentation
    // -------------------------------------------------------------------------

    "findByNamespaceId returns filesystem skills when delegate has none" {
        val configPath = tempConfigPath()
        createSkill(configPath, "code-review", "Code Review", "Reviews PRs")
        createSkill(configPath, "spec", "Spec Writing", "Writes specs")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills shouldHaveSize 2
        skills.map { it.name }.toSet() shouldBe setOf("Code Review", "Spec Writing")
    }

    "findByNamespaceId sets namespaceId on filesystem skills" {
        val configPath = tempConfigPath()
        createSkill(configPath, "code-review", "Code Review", "Reviews PRs")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills.single().namespaceId shouldBe namespaceId
    }

    "findByNamespaceId uses stable UUID derived from skill name" {
        val configPath = tempConfigPath()
        createSkill(configPath, "code-review", "Code Review", "Reviews PRs")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val expectedId = UUID.nameUUIDFromBytes("filesystem-skill:Code Review".toByteArray(Charsets.UTF_8))

        val repo1 = buildRepo(delegate, nsRepo)
        val id1 = repo1.findByNamespaceId(namespaceId).single().id

        val nsRepo2 = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()
        val repo2 = buildRepo(delegate, nsRepo2, ttl = Duration.ZERO)
        val id2 = repo2.findByNamespaceId(namespaceId).single().id

        id1 shouldBe expectedId
        id1 shouldBe id2
    }

    // -------------------------------------------------------------------------
    // Merge: persisted skills win over filesystem
    // -------------------------------------------------------------------------

    "findByNamespaceId places persisted skills first then filesystem additions" {
        val configPath = tempConfigPath()
        createSkill(configPath, "z-skill", "Z", "Z desc")
        createSkill(configPath, "a-skill", "A", "A desc")

        val persisted = listOf(persistedSkill(namespaceId, "Persisted First"))
        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns persisted

        val result = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        result shouldHaveSize 3
        result.first().name shouldBe "Persisted First"
        result.drop(1).map { it.name } shouldBe listOf("A", "Z")
    }

    "findByNamespaceId drops filesystem skill when persisted skill has same name (case-insensitive)" {
        val configPath = tempConfigPath()
        createSkill(configPath, "code-review", "Code Review", "Filesystem desc")

        val persisted = listOf(persistedSkill(namespaceId, "code review", description = "Persisted desc"))
        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns persisted

        val result = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        result shouldHaveSize 1
        result.single().description shouldBe "Persisted desc"
    }

    // -------------------------------------------------------------------------
    // findPlatform — delegate only, filesystem has no platform scope
    // -------------------------------------------------------------------------

    "findPlatform delegates to underlying repository without filesystem augmentation" {
        val delegate = mockk<SkillRepository>()
        val nsRepo = mockk<NamespaceRepository>()
        val platformSkills = listOf(persistedSkill(null, "Global Skill"))

        every { delegate.findPlatform() } returns platformSkills

        val result = buildRepo(delegate, nsRepo).findPlatform()

        result shouldBe platformSkills
        verify(exactly = 1) { delegate.findPlatform() }
        verify(exactly = 0) { nsRepo.findByIds(any()) }
        verify(exactly = 0) { nsRepo.findByParent(any<String>()) }
    }

    // -------------------------------------------------------------------------
    // findByNameInNamespace — delegate first, filesystem fallback for namespace
    // -------------------------------------------------------------------------

    "findByNameInNamespace returns persisted skill when found" {
        val delegate = mockk<SkillRepository>()
        val nsRepo = mockk<NamespaceRepository>()
        val persisted = persistedSkill(namespaceId, "Code Review")

        every { delegate.findByNameInNamespace(namespaceId, "Code Review") } returns persisted

        val result = buildRepo(delegate, nsRepo).findByNameInNamespace(namespaceId, "Code Review")

        result shouldBe persisted
    }

    "findByNameInNamespace falls back to filesystem case-insensitively when delegate returns null" {
        val configPath = tempConfigPath()
        createSkill(configPath, "code-review", "Code Review", "Reviews PRs")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNameInNamespace(namespaceId, "CODE REVIEW") } returns null

        val result = buildRepo(delegate, nsRepo).findByNameInNamespace(namespaceId, "CODE REVIEW")

        result?.name shouldBe "Code Review"
        result?.namespaceId shouldBe namespaceId
    }

    "findByNameInNamespace does not fall back to filesystem when namespaceId is null (platform)" {
        val delegate = mockk<SkillRepository>()
        val nsRepo = mockk<NamespaceRepository>()
        every { delegate.findByNameInNamespace(null, "Global") } returns null

        val result = buildRepo(delegate, nsRepo).findByNameInNamespace(null, "Global")

        result.shouldBeNull()
        verify(exactly = 0) { nsRepo.findByIds(any()) }
    }

    "findByNameInNamespace returns null when skill not found anywhere" {
        val configPath = tempConfigPath()
        createSkill(configPath, "code-review", "Code Review", "Reviews PRs")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNameInNamespace(namespaceId, "Nonexistent") } returns null

        val result = buildRepo(delegate, nsRepo).findByNameInNamespace(namespaceId, "Nonexistent")

        result.shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // findByIds — filesystem augmentation, ordering, and duplicate safety
    // -------------------------------------------------------------------------

    "findByIds returns delegate result when all ids are found in Neo4j" {
        val delegate = mockk<SkillRepository>()
        val nsRepo = mockk<NamespaceRepository>()
        val skill = persistedSkill(namespaceId, "Alpha")
        every { delegate.findByIds(listOf(skill.id), withRemoved = false) } returns listOf(skill)

        val result = buildRepo(delegate, nsRepo).findByIds(listOf(skill.id), withRemoved = false)

        result shouldBe listOf(skill)
        verify(exactly = 0) { nsRepo.findByParent(any<String>()) }
    }

    "findByIds resolves a filesystem skill id that Neo4j does not know" {
        val configPath = tempConfigPath()
        createSkill(configPath, "code-review", "Code Review", "Reviews PRs")

        val delegate = mockk<SkillRepository>()
        val nsRepo = mockk<NamespaceRepository>()
        val fsId = UUID.nameUUIDFromBytes("filesystem-skill:Code Review".toByteArray(Charsets.UTF_8))

        every { delegate.findByIds(listOf(fsId), withRemoved = false) } returns emptyList()
        every { nsRepo.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY) } returns
            listOf(Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns", configPath = configPath.toString()))
        every { nsRepo.findByIds(listOf(namespaceId)) } returns
            listOf(Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns", configPath = configPath.toString()))

        val result = buildRepo(delegate, nsRepo).findByIds(listOf(fsId), withRemoved = false)

        result shouldHaveSize 1
        result.single().name shouldBe "Code Review"
        result.single().namespaceId shouldBe namespaceId
        result.single().id shouldBe fsId
    }

    "findByIds mixes Neo4j and filesystem results and preserves requested order" {
        val configPath = tempConfigPath()
        createSkill(configPath, "code-review", "Code Review", "Reviews PRs")

        val delegate = mockk<SkillRepository>()
        val nsRepo = mockk<NamespaceRepository>()
        val persisted = persistedSkill(namespaceId, "Persisted")
        val fsId = UUID.nameUUIDFromBytes("filesystem-skill:Code Review".toByteArray(Charsets.UTF_8))

        every { delegate.findByIds(listOf(fsId, persisted.id), withRemoved = false) } returns listOf(persisted)
        every { nsRepo.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY) } returns
            listOf(Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns", configPath = configPath.toString()))
        every { nsRepo.findByIds(listOf(namespaceId)) } returns
            listOf(Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns", configPath = configPath.toString()))

        val result = buildRepo(delegate, nsRepo).findByIds(listOf(fsId, persisted.id), withRemoved = false)

        result shouldHaveSize 2
        result.map { it.id } shouldBe listOf(fsId, persisted.id)
    }

    "findByIds omits unknown IDs" {
        val configPath = tempConfigPath()
        createSkill(configPath, "code-review", "Code Review", "Reviews PRs")

        val delegate = mockk<SkillRepository>()
        val nsRepo = mockk<NamespaceRepository>()
        val unknownId = UUID.randomUUID()

        every { delegate.findByIds(listOf(unknownId), withRemoved = false) } returns emptyList()
        every { nsRepo.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY) } returns
            listOf(Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns", configPath = configPath.toString()))
        every { nsRepo.findByIds(listOf(namespaceId)) } returns
            listOf(Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns", configPath = configPath.toString()))

        val result = buildRepo(delegate, nsRepo).findByIds(listOf(unknownId), withRemoved = false)

        result.shouldBeEmpty()
    }

    "findByIds avoids duplicate results when multiple namespaces have skills with same name" {
        val configPath1 = tempConfigPath()
        val configPath2 = tempConfigPath()
        createSkill(configPath1, "code-review", "Code Review", "Reviews PRs")
        createSkill(configPath2, "code-review", "Code Review", "Reviews PRs")

        val nsId1 = UUID.randomUUID()
        val nsId2 = UUID.randomUUID()
        val ns1 = Namespace(metadata = EntityMetadata(id = nsId1), name = "ns1", configPath = configPath1.toString())
        val ns2 = Namespace(metadata = EntityMetadata(id = nsId2), name = "ns2", configPath = configPath2.toString())

        val delegate = mockk<SkillRepository>()
        val nsRepo = mockk<NamespaceRepository>()
        val fsId = UUID.nameUUIDFromBytes("filesystem-skill:Code Review".toByteArray(Charsets.UTF_8))

        every { delegate.findByIds(listOf(fsId), withRemoved = false) } returns emptyList()
        every { nsRepo.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY) } returns listOf(ns1, ns2)
        every { nsRepo.findByIds(listOf(nsId1)) } returns listOf(ns1)
        every { nsRepo.findByIds(listOf(nsId2)) } returns listOf(ns2)

        val result = buildRepo(delegate, nsRepo).findByIds(listOf(fsId), withRemoved = false)

        result shouldHaveSize 1
        result.single().id shouldBe fsId
    }

    // -------------------------------------------------------------------------
    // Writes delegated unchanged
    // -------------------------------------------------------------------------

    "save delegates to underlying repository" {
        val delegate = mockk<SkillRepository>()
        val nsRepo = mockk<NamespaceRepository>()
        val skill = persistedSkill(namespaceId, "Code Review")
        every { delegate.save(skill) } returns skill

        val result = buildRepo(delegate, nsRepo).save(skill)

        result shouldBe skill
        verify(exactly = 1) { delegate.save(skill) }
    }

    "delete delegates to underlying repository" {
        val delegate = mockk<SkillRepository>()
        val nsRepo = mockk<NamespaceRepository>()
        val id = UUID.randomUUID()
        every { delegate.delete(id) } returns true

        val result = buildRepo(delegate, nsRepo).delete(id)

        result shouldBe true
        verify(exactly = 1) { delegate.delete(id) }
    }

    "deleteByParent delegates to underlying repository" {
        val delegate = mockk<SkillRepository>()
        val nsRepo = mockk<NamespaceRepository>()
        every { delegate.deleteByParent(namespaceId) } returns 3

        val result = buildRepo(delegate, nsRepo).deleteByParent(namespaceId)

        result shouldBe 3
        verify(exactly = 1) { delegate.deleteByParent(namespaceId) }
    }

    // -------------------------------------------------------------------------
    // Discovery, parsing, security, bounds and body contract
    // -------------------------------------------------------------------------

    "returns empty list when skills directory does not exist" {
        val configPath = tempConfigPath()
        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId).shouldBeEmpty()
    }

    "discovers valid skills and sets resourceRoot" {
        val configPath = tempConfigPath()
        createSkill(configPath, "code-review", "Code Review", "Reviews PRs")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

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

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills.single().body shouldBe "## Instructions\nWrite specs carefully.\n"
    }

    "body-parsing: single blank separator line after frontmatter is removed" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/sep").createDirectories()
        dir.resolve("SKILL.md").writeText("---\nname: Sep\ndescription: Sep desc\n---\n\nBody line one")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills.single().body shouldBe "Body line one"
    }

    "body-parsing: subsequent leading blank lines beyond the single separator are preserved" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/leading-blanks").createDirectories()
        dir.resolve("SKILL.md").writeText("---\nname: Blanks\ndescription: Blanks desc\n---\n\n\nBody starts here")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills.single().body shouldBe "\nBody starts here"
    }

    "body-parsing: internal blank lines within the body are preserved" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/internal-blanks").createDirectories()
        dir.resolve("SKILL.md").writeText("---\nname: Internal\ndescription: Internal desc\n---\n\nLine one\n\nLine two")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills.single().body shouldBe "Line one\n\nLine two"
    }

    "body-parsing: no synthetic trailing newline is added when the file has none" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/no-trailing").createDirectories()
        dir.resolve("SKILL.md").writeText("---\nname: NoTrail\ndescription: NoTrail desc\n---\n\nLast line, no newline")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills.single().body shouldBe "Last line, no newline"
        skills.single().body shouldNotEndWith "\n"
    }

    "body-parsing: existing trailing newline in the file is preserved" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/trailing").createDirectories()
        dir.resolve("SKILL.md").writeText("---\nname: Trail\ndescription: Trail desc\n---\n\nLast line\n")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills.single().body shouldBe "Last line\n"
    }

    "body-parsing: CRLF input is normalized to LF" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/crlf").createDirectories()
        dir.resolve("SKILL.md").writeText("---\r\nname: Crlf\r\ndescription: Crlf desc\r\n---\r\n\r\nLine one\r\nLine two\r\n")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        val skill = skills.single()
        skill.name shouldBe "Crlf"
        skill.body shouldBe "Line one\nLine two\n"
    }

    "body is not truncated by name/description char caps" {
        val configPath = tempConfigPath()
        val longBody = "x".repeat(FilesystemSkillRepository.MAX_SKILL_NAME_CHARS + 200)
        createSkill(configPath, "long-body", "Long", "Desc", body = longBody)

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills.single().body.trim() shouldBe longBody
    }

    "orders skills by skillRelativePath" {
        val configPath = tempConfigPath()
        createSkill(configPath, "z-skill", "Z", "Z desc")
        createSkill(configPath, "a-skill", "A", "A desc")
        createSkill(configPath, "m-skill", "M", "M desc")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills.map { it.skillRelativePath } shouldBe listOf("a-skill", "m-skill", "z-skill")
    }

    "skips malformed frontmatter" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/malformed").createDirectories()
        dir.resolve("SKILL.md").writeText("No frontmatter here.")
        createSkill(configPath, "valid", "Valid", "Valid desc")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills shouldHaveSize 1
        skills.single().name shouldBe "Valid"
    }

    "skips skills with blank name or description" {
        val configPath = tempConfigPath()
        createSkill(configPath, "no-name", null, "Desc")
        createSkill(configPath, "blank-name", "   ", "Desc")
        createSkill(configPath, "no-desc", "Skill", null)
        createSkill(configPath, "valid", "Valid", "Valid desc")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills shouldHaveSize 1
        skills.single().name shouldBe "Valid"
    }

    "deduplicates by name case-insensitively, first-by-path wins" {
        val configPath = tempConfigPath()
        createSkill(configPath, "a-skill", "DuplicateName", "First")
        createSkill(configPath, "b-skill", "duplicatename", "Second")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills shouldHaveSize 1
        skills.single().description shouldBe "First"
    }

    "truncates name and description exceeding char caps with ellipsis" {
        val configPath = tempConfigPath()
        val longName = "N".repeat(FilesystemSkillRepository.MAX_SKILL_NAME_CHARS + 50)
        val longDesc = "D".repeat(FilesystemSkillRepository.MAX_SKILL_DESCRIPTION_CHARS + 100)
        createSkill(configPath, "long", longName, longDesc)

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

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

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills.single().name shouldBe "Spaced Out"
        skills.single().description shouldBe "Line one Line two"
    }

    "skips oversized SKILL.md" {
        val configPath = tempConfigPath()
        val oversizedDir = configPath.resolve("skills/big").createDirectories()
        val padding = "x".repeat(FilesystemSkillRepository.MAX_SKILL_FILE_BYTES.toInt() + 1)
        oversizedDir.resolve("SKILL.md").writeText("---\nname: Big\ndescription: Too big\n---\n$padding")
        createSkill(configPath, "normal", "Normal", "Fits fine")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills shouldHaveSize 1
        skills.single().name shouldBe "Normal"
    }

    "skips invalid YAML syntax without propagating exception" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/bad-yaml").createDirectories()
        dir.resolve("SKILL.md").writeText("---\nname: [unclosed\ndescription: broken\n---\n")
        createSkill(configPath, "valid", "Valid", "Valid desc")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills shouldHaveSize 1
    }

    "skips SKILL.md exceeding MAX_WALK_DEPTH" {
        val configPath = tempConfigPath()
        val deepPath = (1..(FilesystemSkillRepository.MAX_WALK_DEPTH)).joinToString("/") { "d$it" }
        val deepDir = configPath.resolve("skills/$deepPath").createDirectories()
        deepDir.resolve("SKILL.md").writeText("---\nname: Deep\ndescription: Too deep\n---\n## Body\n")
        createSkill(configPath, "shallow", "Shallow", "Shallow desc")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills shouldHaveSize 1
        skills.single().name shouldBe "Shallow"
    }

    "ignores non-SKILL.md files" {
        val configPath = tempConfigPath()
        val dir = configPath.resolve("skills/myskill").createDirectories()
        dir.resolve("SKILL.md").writeText("---\nname: Real Skill\ndescription: Real desc\n---\n## Body\n")
        dir.resolve("notes.yaml").writeText("name: Should Not Appear")
        dir.resolve("README.md").writeText("# ignored")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

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
            val delegate = mockk<SkillRepository>()
            val nsRepo = nsRepoWith(namespaceId, configPath.toString())
            every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

            val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)
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
            val delegate = mockk<SkillRepository>()
            val nsRepo = nsRepoWith(namespaceId, configPath.toString())
            every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

            val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)
            skills shouldHaveSize 1
            skills.single().name shouldBe "Inside"
        }
    }

    "second findByNamespaceId call within TTL returns cached result" {
        val configPath = tempConfigPath()
        createSkill(configPath, "original", "Original", "First skill")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val r = buildRepo(delegate, nsRepo)
        val first = r.findByNamespaceId(namespaceId)
        first shouldHaveSize 1

        createSkill(configPath, "new-skill", "New Skill", "Added after cache")

        val second = r.findByNamespaceId(namespaceId)
        second shouldHaveSize 1
        second.single().name shouldBe "Original"
    }

    "nested skills have correct skillRelativePath" {
        val configPath = tempConfigPath()
        createSkill(configPath, "backend/kotlin", "Kotlin Backend", "Kotlin guidelines")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills.single().skillRelativePath shouldBe "backend/kotlin"
    }

    "catalog not emitting project-root-relative paths" {
        val configPath = tempConfigPath()
        createSkill(configPath, "product/spec", "Spec Writing", "Writes specs")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

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

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills shouldHaveSize FilesystemSkillRepository.MAX_SKILL_COUNT
        skills.first().name shouldBe "Skill0000"
        skills.first().description shouldBe "Earlier path for Skill0000"
        val includedNames = skills.map { it.name }.toSet()
        val lastGeneratedId = (total - 1).toString().padStart(4, '0')
        includedNames shouldNotContainElement "Skill$lastGeneratedId"
    }
})
