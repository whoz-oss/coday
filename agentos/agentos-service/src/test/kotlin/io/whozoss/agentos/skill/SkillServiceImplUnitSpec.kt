package io.whozoss.agentos.skill

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

class SkillServiceImplUnitSpec : StringSpec({

    val repository = mockk<FilesystemSkillRepository>()
    val namespaceService = mockk<NamespaceService>()
    val service = SkillServiceImpl(repository, namespaceService)

    val namespaceId: UUID = UUID.randomUUID()

    fun stubNamespace(configPath: String?) {
        every { namespaceService.findById(namespaceId, any()) } returns
            Namespace(
                metadata = EntityMetadata(id = namespaceId),
                name = "ns",
                configPath = configPath,
            )
    }

    val skill1 = Skill(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        name = "spec-writing",
        description = "Writes specs",
        body = "## Spec\nBody",
        skillRelativePath = "product/spec-writing",
        resourceRoot = "/tmp/skills/product/spec-writing",
    )
    val skill2 = Skill(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        name = "jira-writing",
        description = "Writes tickets",
        body = "## Jira\nBody",
        skillRelativePath = "product/jira-writing",
        resourceRoot = "/tmp/skills/product/jira-writing",
    )
    val skill3 = Skill(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        name = "branch-creation",
        description = "Creates branches",
        body = "## Branch\nBody",
        skillRelativePath = "core/branch-creation",
        resourceRoot = "/tmp/skills/core/branch-creation",
    )
    val skill4 = Skill(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        name = "adversarial-review",
        description = "Reviews diffs",
        body = "## Review\nBody",
        skillRelativePath = "review/adversarial-review",
        resourceRoot = "/tmp/skills/review/adversarial-review",
    )
    val all = listOf(skill3, skill1, skill2, skill4) // discovery order: core, product, review

    // -------------------------------------------------------------------------
    // Null / empty selectors → no skills (new semantics)
    // -------------------------------------------------------------------------

    "filterSkills with empty selectors returns empty list" {
        service.filterSkills(all, emptyList()).shouldBeEmpty()
    }

    "filterSkills with empty list returns empty list" {
        service.filterSkills(all, emptyList()).shouldBeEmpty()
    }

    // -------------------------------------------------------------------------
    // Wildcard
    // -------------------------------------------------------------------------

    "filterSkills with wildcard returns all skills" {
        service.filterSkills(all, listOf("*")) shouldBe all
    }

    // -------------------------------------------------------------------------
    // Folder prefix selectors
    // -------------------------------------------------------------------------

    "filterSkills with folder prefix core/** returns only core skills" {
        service.filterSkills(all, listOf("core/**")) shouldBe listOf(skill3)
    }

    "filterSkills with folder prefix core/* returns only core skills" {
        service.filterSkills(all, listOf("core/*")) shouldBe listOf(skill3)
    }

    "filterSkills with product/** returns both product skills" {
        service.filterSkills(all, listOf("product/**")) shouldBe listOf(skill1, skill2)
    }

    // -------------------------------------------------------------------------
    // Exact selectors
    // -------------------------------------------------------------------------

    "filterSkills exact by skillRelativePath" {
        service.filterSkills(all, listOf("product/spec-writing")) shouldBe listOf(skill1)
    }

    "filterSkills exact by skillRelativePath with SKILL.md suffix" {
        service.filterSkills(all, listOf("product/spec-writing/SKILL.md")) shouldBe listOf(skill1)
    }

    "filterSkills exact by frontmatter name" {
        service.filterSkills(all, listOf("spec-writing")) shouldBe listOf(skill1)
    }

    // -------------------------------------------------------------------------
    // Combined selectors with deduplication
    // -------------------------------------------------------------------------

    "filterSkills combined selectors deduplicate and preserve discovery order" {
        val result = service.filterSkills(all, listOf("core/**", "product/**", "review/adversarial-review", "core/branch-creation"))
        result shouldBe listOf(skill3, skill1, skill2, skill4)
    }

    // -------------------------------------------------------------------------
    // Unknown selector warns and is ignored
    // -------------------------------------------------------------------------

    "filterSkills unknown selector is ignored" {
        val result = service.filterSkills(all, listOf("core/**", "nonexistent/**"))
        result shouldBe listOf(skill3)
    }

    // -------------------------------------------------------------------------
    // Empty-prefix guard
    // -------------------------------------------------------------------------

    "filterSkills glob with empty prefix does not match everything" {
        service.filterSkills(all, listOf("/**")).shouldBeEmpty()
        service.filterSkills(all, listOf("/*")).shouldBeEmpty()
    }

    // -------------------------------------------------------------------------
    // Root-level skill (skillRelativePath == "")
    // -------------------------------------------------------------------------

    "filterSkills root-level skill selectable by name and by SKILL.md" {
        val rootSkill = Skill(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            name = "Root Skill",
            description = "Root desc",
            body = "## Body",
            skillRelativePath = "",
            resourceRoot = "/tmp/skills",
        )
        val nested = Skill(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            name = "Nested",
            description = "Nested desc",
            body = "## Body",
            skillRelativePath = "nested/child",
            resourceRoot = "/tmp/skills/nested/child",
        )
        val skills = listOf(rootSkill, nested)

        service.filterSkills(skills, listOf("Root Skill")) shouldBe listOf(rootSkill)
        service.filterSkills(skills, listOf("SKILL.md")) shouldBe listOf(rootSkill)
        service.filterSkills(skills, listOf("/**")).shouldBeEmpty()
        service.filterSkills(skills, listOf("/*")).shouldBeEmpty()
    }

    // -------------------------------------------------------------------------
    // findSkills — null/empty configPath and selector gating
    // -------------------------------------------------------------------------

    "findSkills returns empty when namespace has no configPath" {
        stubNamespace(configPath = null)
        every { repository.findAll(any()) } returns all

        val result = kotlinx.coroutines.runBlocking { service.findSkills(namespaceId, listOf("*")) }
        result.shouldBeEmpty()
    }

    "findSkills returns empty when namespace is unknown" {
        every { namespaceService.findById(namespaceId, any()) } returns null
        every { repository.findAll(any()) } returns all

        val result = kotlinx.coroutines.runBlocking { service.findSkills(namespaceId, listOf("*")) }
        result.shouldBeEmpty()
    }

    "findSkills returns empty when selectors is null, without touching the repository" {
        stubNamespace("/cfg")

        val result = kotlinx.coroutines.runBlocking { service.findSkills(namespaceId, null) }
        result.shouldBeEmpty()
    }

    "findSkills returns empty when selectors is empty" {
        stubNamespace("/cfg")

        val result = kotlinx.coroutines.runBlocking { service.findSkills(namespaceId, emptyList()) }
        result.shouldBeEmpty()
    }

    "findSkills wildcard returns all discovered skills" {
        stubNamespace("/cfg")
        every { repository.findAll("/cfg") } returns all

        val result = kotlinx.coroutines.runBlocking { service.findSkills(namespaceId, listOf("*")) }
        result shouldBe all
    }

    // -------------------------------------------------------------------------
    // findSkillByName
    // -------------------------------------------------------------------------

    "findSkillByName returns skill when found case-insensitively" {
        stubNamespace("/cfg")
        every { repository.findAll("/cfg") } returns all

        val result = kotlinx.coroutines.runBlocking { service.findSkillByName(namespaceId, "SPEC-WRITING") }
        result shouldBe skill1
    }

    "findSkillByName returns null when not found" {
        stubNamespace("/cfg")
        every { repository.findAll("/cfg") } returns all

        val result = kotlinx.coroutines.runBlocking { service.findSkillByName(namespaceId, "nonexistent") }
        result shouldBe null
    }

    "findSkillByName returns null when namespace has no configPath" {
        stubNamespace(configPath = null)

        val result = kotlinx.coroutines.runBlocking { service.findSkillByName(namespaceId, "spec-writing") }
        result shouldBe null
    }
})
