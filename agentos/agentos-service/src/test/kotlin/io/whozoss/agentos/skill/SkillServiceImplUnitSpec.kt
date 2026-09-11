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
    )
    val skill2 = Skill(
        metadata = io.whozoss.agentos.sdk.entity.EntityMetadata(),
        namespaceId = namespaceId,
        name = "jira-writing",
        description = "Writes tickets",
        body = "## Jira\nBody",
    )
    val skill3 = Skill(
        metadata = io.whozoss.agentos.sdk.entity.EntityMetadata(),
        namespaceId = namespaceId,
        name = "branch-creation",
        description = "Creates branches",
        body = "## Branch\nBody",
    )
    val skill4 = Skill(
        metadata = io.whozoss.agentos.sdk.entity.EntityMetadata(),
        namespaceId = namespaceId,
        name = "adversarial-review",
        description = "Reviews diffs",
        body = "## Review\nBody",
    )
    val all = listOf(skill3, skill1, skill2, skill4)

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
        every { repository.findByIds(listOf(existing.metadata.id), false) } returns listOf(existing)
        every { repository.findByNameInNamespace(namespaceId, "My Skill") } returns existing
        every { repository.save(any()) } answers { firstArg() }

        val updated = service.update(existing.copy(description = "Updated desc"))
        updated.description shouldBe "Updated desc"
    }

    "update throws 409 when renaming collides with another entity in same scope" {
        val existing = Skill(name = "Skill A", description = "D", body = "B", namespaceId = namespaceId)
        val other = Skill(name = "Skill B", description = "D", body = "B", namespaceId = namespaceId)

        every { repository.findByIds(listOf(existing.metadata.id), true) } returns listOf(existing)
        every { repository.findByIds(listOf(existing.metadata.id), false) } returns listOf(existing)
        every { repository.findByNameInNamespace(namespaceId, "Skill B") } returns other

        val ex = shouldThrow<ResponseStatusException> {
            service.update(existing.copy(name = "Skill B"))
        }
        ex.statusCode.value() shouldBe 409
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

    // -------------------------------------------------------------------------
    // Platform + Namespace Shadowing Resolution
    // -------------------------------------------------------------------------

    "findSkills merges namespace and platform skills on wildcard, namespace wins on collision" {
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

    "findSkills with specific names pushes down query to repository with shadowing" {
        val nsSkill = Skill(name = "spec-writing", description = "Namespace spec", body = "NS body", namespaceId = namespaceId)
        val platformSkill = Skill(name = "spec-writing", description = "Platform spec", body = "Platform body", namespaceId = null)

        every { repository.findByNamespaceIdAndNames(namespaceId, listOf("spec-writing")) } returns listOf(nsSkill, platformSkill)

        val skills = kotlinx.coroutines.runBlocking { service.findSkills(namespaceId, listOf("spec-writing")) }

        skills shouldHaveSize 1
        skills.single().description shouldBe "Namespace spec"
        verify(exactly = 1) { repository.findByNamespaceIdAndNames(namespaceId, listOf("spec-writing")) }
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

    // -------------------------------------------------------------------------
    // Selectors
    // -------------------------------------------------------------------------

    "filterSkills with wildcard returns all skills" {
        service.filterSkills(all, listOf("*")) shouldBe all
    }

    "filterSkills with exact name returns matched skill" {
        service.filterSkills(all, listOf("spec-writing")) shouldBe listOf(skill1)
        service.filterSkills(all, listOf("SPEC-WRITING")) shouldBe listOf(skill1)
    }

    "filterSkills with multiple names returns all matched skills" {
        service.filterSkills(all, listOf("spec-writing", "jira-writing")) shouldBe listOf(skill1, skill2)
    }

    "filterSkills with empty selectors returns empty list" {
        service.filterSkills(all, emptyList()).shouldBeEmpty()
    }
})
