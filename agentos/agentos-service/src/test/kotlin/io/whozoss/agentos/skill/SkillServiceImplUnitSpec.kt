package io.whozoss.agentos.skill

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class SkillServiceImplUnitSpec : StringSpec({

    val repository = mockk<SkillRepository>()
    val service = SkillServiceImpl(repository)

    val namespaceId: UUID = UUID.randomUUID()

    val skill1 = Skill(
        metadata = io.whozoss.agentos.sdk.entity.EntityMetadata(),
        namespaceId = namespaceId,
        name = "spec-writing",
        description = "Writes specs",
        body = "## Spec\nBody",
        skillRelativePath = "product/spec-writing",
        resourceRoot = "/tmp/skills/product/spec-writing",
    )
    val skill2 = Skill(
        metadata = io.whozoss.agentos.sdk.entity.EntityMetadata(),
        namespaceId = namespaceId,
        name = "jira-writing",
        description = "Writes tickets",
        body = "## Jira\nBody",
        skillRelativePath = "product/jira-writing",
        resourceRoot = "/tmp/skills/product/jira-writing",
    )
    val skill3 = Skill(
        metadata = io.whozoss.agentos.sdk.entity.EntityMetadata(),
        namespaceId = namespaceId,
        name = "branch-creation",
        description = "Creates branches",
        body = "## Branch\nBody",
        skillRelativePath = "core/branch-creation",
        resourceRoot = "/tmp/skills/core/branch-creation",
    )
    val skill4 = Skill(
        metadata = io.whozoss.agentos.sdk.entity.EntityMetadata(),
        namespaceId = namespaceId,
        name = "adversarial-review",
        description = "Reviews diffs",
        body = "## Review\nBody",
        skillRelativePath = "review/adversarial-review",
        resourceRoot = "/tmp/skills/review/adversarial-review",
    )
    val coreDirect = Skill(
        metadata = io.whozoss.agentos.sdk.entity.EntityMetadata(),
        namespaceId = namespaceId,
        name = "direct",
        description = "Direct under core",
        body = "## Direct",
        skillRelativePath = "core/direct",
        resourceRoot = "/tmp/skills/core/direct",
    )
    val coreNestedDeep = Skill(
        metadata = io.whozoss.agentos.sdk.entity.EntityMetadata(),
        namespaceId = namespaceId,
        name = "deep",
        description = "Deep under core",
        body = "## Deep",
        skillRelativePath = "core/nested/deep",
        resourceRoot = "/tmp/skills/core/nested/deep",
    )
    val dbSkill = Skill(
        metadata = io.whozoss.agentos.sdk.entity.EntityMetadata(),
        namespaceId = namespaceId,
        name = "db-skill",
        description = "Persisted in DB",
        body = "## DB\nBody",
        skillRelativePath = null,
        resourceRoot = null,
    )
    val all = listOf(skill3, skill1, skill2, skill4) // discovery order: core, product, review

    beforeEach {
        clearMocks(repository)
    }

    // -------------------------------------------------------------------------
    // CRUD Operations & Validation
    // -------------------------------------------------------------------------

    "create saves skill when name is unique" {
        every { repository.findByNameInNamespace(namespaceId, "New Skill") } returns null
        every { repository.save(any()) } answers { firstArg() }

        val created = service.create(Skill(name = "New Skill", description = "D", body = "B", namespaceId = namespaceId))

        created.name shouldBe "New Skill"
        verify(exactly = 1) { repository.save(any()) }
    }

    "create throws 409 when name already exists in same namespace" {
        val existing = Skill(name = "Existing", description = "D", body = "B", namespaceId = namespaceId)
        every { repository.findByNameInNamespace(namespaceId, "Existing") } returns existing

        val ex = shouldThrow<ResponseStatusException> {
            service.create(Skill(name = "Existing", description = "D2", body = "B2", namespaceId = namespaceId))
        }
        ex.statusCode.value() shouldBe 409
    }

    "create allows same name in different namespace or platform scope" {
        val otherNs = UUID.randomUUID()
        every { repository.findByNameInNamespace(otherNs, "Existing") } returns null
        every { repository.save(any()) } answers { firstArg() }

        val created = service.create(Skill(name = "Existing", description = "D", body = "B", namespaceId = otherNs))
        created.name shouldBe "Existing"
    }

    "update saves skill when name is unchanged on same entity" {
        val existing = Skill(name = "My Skill", description = "D", body = "B", namespaceId = namespaceId)
        every { repository.findByIds(listOf(existing.metadata.id), true) } returns listOf(existing)
        every { repository.findByNameInNamespace(namespaceId, "My Skill") } returns existing
        every { repository.save(any()) } answers { firstArg() }

        val updated = service.update(existing.copy(description = "Updated desc"))
        updated.description shouldBe "Updated desc"
    }

    "update throws 409 when renaming collides with another entity in same scope" {
        val existing = Skill(name = "Skill A", description = "D", body = "B", namespaceId = namespaceId)
        val other = Skill(name = "Skill B", description = "D", body = "B", namespaceId = namespaceId)

        every { repository.findByIds(listOf(existing.metadata.id), true) } returns listOf(existing)
        every { repository.findByNameInNamespace(namespaceId, "Skill B") } returns other

        val ex = shouldThrow<ResponseStatusException> {
            service.update(existing.copy(name = "Skill B"))
        }
        ex.statusCode.value() shouldBe 409
    }

    "update throws 400 when attempting to mutate filesystem-backed skill" {
        val fsSkill = Skill(
            name = "Fs Skill",
            description = "D",
            body = "B",
            namespaceId = namespaceId,
            skillRelativePath = "core/fs",
            resourceRoot = "/tmp/fs",
        )
        every { repository.findByIds(listOf(fsSkill.metadata.id), true) } returns listOf(fsSkill)

        val ex = shouldThrow<ResponseStatusException> {
            service.update(fsSkill.copy(description = "Mutated"))
        }
        ex.statusCode.value() shouldBe 400
    }

    "delete soft-deletes DB skill and returns true" {
        val dbEntity = Skill(name = "DB Skill", description = "D", body = "B", namespaceId = namespaceId)
        every { repository.findByIds(listOf(dbEntity.metadata.id), false) } returns listOf(dbEntity)
        every { repository.delete(dbEntity.metadata.id) } returns true

        val deleted = service.delete(dbEntity.metadata.id)
        deleted shouldBe true
        verify(exactly = 1) { repository.delete(dbEntity.metadata.id) }
    }

    "delete returns false when skill not found" {
        val unknownId = UUID.randomUUID()
        every { repository.findByIds(listOf(unknownId), false) } returns emptyList()

        val deleted = service.delete(unknownId)
        deleted shouldBe false
        verify(exactly = 0) { repository.delete(unknownId) }
    }

    "delete throws 400 when attempting to delete filesystem-backed skill" {
        val fsSkill = Skill(
            name = "Fs Skill",
            description = "D",
            body = "B",
            namespaceId = namespaceId,
            skillRelativePath = "core/fs",
            resourceRoot = "/tmp/fs",
        )
        every { repository.findByIds(listOf(fsSkill.metadata.id), false) } returns listOf(fsSkill)

        val ex = shouldThrow<ResponseStatusException> {
            service.delete(fsSkill.metadata.id)
        }
        ex.statusCode.value() shouldBe 400
    }

    // -------------------------------------------------------------------------
    // Platform + Namespace Shadowing Resolution
    // -------------------------------------------------------------------------

    "findSkills merges namespace and platform skills, namespace wins on name collision" {
        val nsSkill = Skill(name = "Code Review", description = "Namespace version", body = "NS body", namespaceId = namespaceId)
        val platformSkill1 = Skill(name = "code review", description = "Platform version", body = "Platform body", namespaceId = null)
        val platformSkill2 = Skill(name = "Global Tool", description = "Platform global", body = "Global body", namespaceId = null)

        every { repository.findByNamespaceId(namespaceId) } returns listOf(nsSkill)
        every { repository.findPlatform() } returns listOf(platformSkill1, platformSkill2)

        val skills = kotlinx.coroutines.runBlocking { service.findSkills(namespaceId, listOf("*")) }

        skills shouldHaveSize 2
        skills.first().name shouldBe "Code Review"
        skills.first().description shouldBe "Namespace version"
        skills.last().name shouldBe "Global Tool"
    }

    "findSkillByName queries namespace first, then platform fallback" {
        val platformSkill = Skill(name = "Global", description = "Platform global", body = "Global body", namespaceId = null)

        every { repository.findByNameInNamespace(namespaceId, "Global") } returns null
        every { repository.findByNameInNamespace(null, "Global") } returns platformSkill

        val found = kotlinx.coroutines.runBlocking { service.findSkillByName(namespaceId, "Global") }

        found.shouldNotBeNull()
        found.name shouldBe "Global"
        verify(exactly = 1) { repository.findByNameInNamespace(namespaceId, "Global") }
        verify(exactly = 1) { repository.findByNameInNamespace(null, "Global") }
    }

    "findSkillByName returns namespace skill without consulting platform when found" {
        val nsSkill = Skill(name = "Local", description = "Namespace local", body = "NS body", namespaceId = namespaceId)

        every { repository.findByNameInNamespace(namespaceId, "Local") } returns nsSkill

        val found = kotlinx.coroutines.runBlocking { service.findSkillByName(namespaceId, "Local") }

        found.shouldNotBeNull()
        found.name shouldBe "Local"
        verify(exactly = 1) { repository.findByNameInNamespace(namespaceId, "Local") }
        verify(exactly = 0) { repository.findByNameInNamespace(null, any()) }
    }

    // -------------------------------------------------------------------------
    // Null / empty selectors → no skills
    // -------------------------------------------------------------------------

    "filterSkills with empty selectors returns empty list" {
        service.filterSkills(all, emptyList()).shouldBeEmpty()
    }

    "filterSkills with empty list returns empty list" {
        service.filterSkills(all, emptyList()).shouldBeEmpty()
    }

    "findSkills with null or empty selectors does not touch repository" {
        val res1 = kotlinx.coroutines.runBlocking { service.findSkills(namespaceId, null) }
        val res2 = kotlinx.coroutines.runBlocking { service.findSkills(namespaceId, emptyList()) }

        res1.shouldBeEmpty()
        res2.shouldBeEmpty()
        verify(exactly = 0) { repository.findByNamespaceId(any()) }
        verify(exactly = 0) { repository.findPlatform() }
    }

    // -------------------------------------------------------------------------
    // DB-stored skill (null skillRelativePath) selector matching
    // -------------------------------------------------------------------------

    "DB skill with null skillRelativePath matches wildcard and exact name, but NOT folder/path selectors" {
        val mixed = listOf(skill1, dbSkill)

        service.filterSkills(mixed, listOf("*")) shouldBe mixed
        service.filterSkills(mixed, listOf("db-skill")) shouldBe listOf(dbSkill)
        service.filterSkills(mixed, listOf("DB-SKILL")) shouldBe listOf(dbSkill)
        service.filterSkills(mixed, listOf("product/**")) shouldBe listOf(skill1)
        service.filterSkills(mixed, listOf("core/*")).shouldBeEmpty()
        service.filterSkills(mixed, listOf("product/spec-writing")) shouldBe listOf(skill1)
        service.filterSkills(mixed, listOf("db-skill/SKILL.md")).shouldBeEmpty()
    }

    // -------------------------------------------------------------------------
    // Recursive vs single-level folder prefix distinction
    // -------------------------------------------------------------------------

    "single-star matches only direct children under prefix, while double-star matches recursive subtree" {
        val coreSkills = listOf(coreDirect, coreNestedDeep)

        service.filterSkills(coreSkills, listOf("core/*")) shouldBe listOf(coreDirect)
        service.filterSkills(coreSkills, listOf("core/**")) shouldBe listOf(coreDirect, coreNestedDeep)
    }

    // -------------------------------------------------------------------------
    // Wildcard & Preserved Selector Tests
    // -------------------------------------------------------------------------

    "filterSkills with wildcard returns all skills" {
        service.filterSkills(all, listOf("*")) shouldBe all
    }

    "filterSkills with folder prefix core/** returns only core skills" {
        service.filterSkills(all, listOf("core/**")) shouldBe listOf(skill3)
    }

    "filterSkills with folder prefix core/* returns only core skills" {
        service.filterSkills(all, listOf("core/*")) shouldBe listOf(skill3)
    }

    "filterSkills with product/** returns both product skills" {
        service.filterSkills(all, listOf("product/**")) shouldBe listOf(skill1, skill2)
    }

    "filterSkills exact by skillRelativePath" {
        service.filterSkills(all, listOf("product/spec-writing")) shouldBe listOf(skill1)
    }

    "filterSkills exact by skillRelativePath with SKILL.md suffix" {
        service.filterSkills(all, listOf("product/spec-writing/SKILL.md")) shouldBe listOf(skill1)
    }

    "filterSkills exact by frontmatter name" {
        service.filterSkills(all, listOf("spec-writing")) shouldBe listOf(skill1)
    }

    "filterSkills combined selectors deduplicate and preserve discovery order" {
        val result = service.filterSkills(all, listOf("core/**", "product/**", "review/adversarial-review", "core/branch-creation"))
        result shouldBe listOf(skill3, skill1, skill2, skill4)
    }

    "filterSkills unknown selector is ignored" {
        val result = service.filterSkills(all, listOf("core/**", "nonexistent/**"))
        result shouldBe listOf(skill3)
    }

    "filterSkills glob with empty prefix does not match everything" {
        service.filterSkills(all, listOf("/**")).shouldBeEmpty()
        service.filterSkills(all, listOf("/*")).shouldBeEmpty()
    }

    "filterSkills root-level skill selectable by name and by SKILL.md" {
        val rootSkill = Skill(
            metadata = io.whozoss.agentos.sdk.entity.EntityMetadata(),
            namespaceId = namespaceId,
            name = "Root Skill",
            description = "Root desc",
            body = "## Body",
            skillRelativePath = "",
            resourceRoot = "/tmp/skills",
        )
        val nested = Skill(
            metadata = io.whozoss.agentos.sdk.entity.EntityMetadata(),
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
})
