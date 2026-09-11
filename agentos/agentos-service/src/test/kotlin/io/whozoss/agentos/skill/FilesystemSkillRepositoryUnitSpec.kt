package io.whozoss.agentos.skill

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
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
        skillDirName: String,
        name: String?,
        description: String?,
        extraFrontmatter: String = "",
        body: String = "## Guidelines\nDo this.",
    ): Path {
        val dir = configPath.resolve("skills").resolve(skillDirName).createDirectories()
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

    // -------------------------------------------------------------------------
    // Flat filesystem discovery
    // -------------------------------------------------------------------------

    "findByNamespaceId returns filesystem skills in flat structure" {
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

    "discovers auxiliary resources in skill folder" {
        val configPath = tempConfigPath()
        val skillFile = createSkill(configPath, "code-review", "Code Review", "Reviews PRs")
        val skillDir = skillFile.parent
        skillDir.resolve("references").createDirectories().resolve("guide.md").writeText("# Review Guide")
        skillDir.resolve("scripts").createDirectories().resolve("lint.sh").writeText("echo lint")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills shouldHaveSize 1
        val skill = skills.single()
        skill.resources["references/guide.md"] shouldBe "# Review Guide"
        skill.resources["scripts/lint.sh"] shouldBe "echo lint"
    }

    "two namespaces with same skill name have different UUIDs and different bodies without collision" {
        val configPath1 = tempConfigPath()
        val configPath2 = tempConfigPath()
        createSkill(configPath1, "code-review", "Code Review", "Reviews PRs - Namespace 1", body = "Body 1")
        createSkill(configPath2, "code-review", "Code Review", "Reviews PRs - Namespace 2", body = "Body 2")

        val nsId1 = UUID.randomUUID()
        val nsId2 = UUID.randomUUID()
        val ns1 = Namespace(metadata = EntityMetadata(id = nsId1), name = "ns1", configPath = configPath1.toString())
        val ns2 = Namespace(metadata = EntityMetadata(id = nsId2), name = "ns2", configPath = configPath2.toString())

        val delegate = mockk<SkillRepository>()
        val nsRepo = mockk<NamespaceRepository>()

        val id1 = FilesystemSkillRepository.computeFilesystemSkillId(nsId1, "Code Review")
        val id2 = FilesystemSkillRepository.computeFilesystemSkillId(nsId2, "Code Review")

        (id1 == id2) shouldBe false

        every { delegate.findByIds(listOf(id1), withRemoved = false) } returns emptyList()
        every { delegate.findByIds(listOf(id2), withRemoved = false) } returns emptyList()
        every { delegate.findByIds(listOf(id1, id2), withRemoved = false) } returns emptyList()
        every { nsRepo.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY) } returns listOf(ns1, ns2)
        every { nsRepo.findByIds(listOf(nsId1)) } returns listOf(ns1)
        every { nsRepo.findByIds(listOf(nsId2)) } returns listOf(ns2)

        val repo = buildRepo(delegate, nsRepo)

        val found1 = repo.findByIds(listOf(id1), withRemoved = false)
        found1 shouldHaveSize 1
        found1.single().id shouldBe id1
        found1.single().namespaceId shouldBe nsId1
        found1.single().body.trim() shouldBe "Body 1"

        val found2 = repo.findByIds(listOf(id2), withRemoved = false)
        found2 shouldHaveSize 1
        found2.single().id shouldBe id2
        found2.single().namespaceId shouldBe nsId2
        found2.single().body.trim() shouldBe "Body 2"

        val foundBoth = repo.findByIds(listOf(id1, id2), withRemoved = false)
        foundBoth shouldHaveSize 2
        foundBoth.map { it.body.trim() } shouldBe listOf("Body 1", "Body 2")
    }

    "ignores nested skills deeper than flat depth" {
        val configPath = tempConfigPath()
        val nestedDir = configPath.resolve("skills/backend/kotlin").createDirectories()
        nestedDir.resolve("SKILL.md").writeText("---\nname: Deep Skill\ndescription: Too deep\n---\nBody")
        createSkill(configPath, "shallow", "Shallow", "Shallow desc")

        val delegate = mockk<SkillRepository>()
        val nsRepo = nsRepoWith(namespaceId, configPath.toString())
        every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

        val skills = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

        skills shouldHaveSize 1
        skills.single().name shouldBe "Shallow"
    }
})
