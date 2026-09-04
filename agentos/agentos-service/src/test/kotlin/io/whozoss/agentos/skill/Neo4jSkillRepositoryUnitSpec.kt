package io.whozoss.agentos.skill

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceNode
import io.whozoss.agentos.persistence.Neo4jChildLinkService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.repository.findByIdOrNull
import java.util.UUID

class Neo4jSkillRepositoryUnitSpec : StringSpec({

    val neo4jRepository = mockk<SkillNodeNeo4jRepository>()
    val childLinkService = mockk<Neo4jChildLinkService>(relaxed = true)
    val repo = Neo4jSkillRepository(neo4jRepository, childLinkService)

    val namespaceId = UUID.randomUUID()

    beforeEach {
        clearMocks(neo4jRepository, childLinkService)
    }

    // -------------------------------------------------------------------------
    // save
    // -------------------------------------------------------------------------

    "save persists node and links to Namespace when namespaceId is non-null" {
        val skill = Skill(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            name = "Code Review",
            description = "Reviews PRs",
            body = "Body",
        )
        val savedNode = SkillNode.fromDomain(skill)

        every { neo4jRepository.save(any<SkillNode>()) } returns savedNode

        val saved = repo.save(skill)

        saved.id shouldBe skill.id
        saved.name shouldBe "Code Review"
        verify(exactly = 1) { neo4jRepository.save(any<SkillNode>()) }
        verify(exactly = 1) {
            childLinkService.link("Skill", savedNode.id, "Namespace", namespaceId.toString())
        }
    }

    "save skips link step for platform skill (namespaceId is null)" {
        val platformSkill = Skill(
            metadata = EntityMetadata(),
            namespaceId = null,
            name = "Global Skill",
            description = "Desc",
            body = "Body",
        )
        val savedNode = SkillNode.fromDomain(platformSkill)

        every { neo4jRepository.save(any<SkillNode>()) } returns savedNode

        val saved = repo.save(platformSkill)

        saved.id shouldBe platformSkill.id
        saved.namespaceId.shouldBeNull()
        verify(exactly = 1) { neo4jRepository.save(any<SkillNode>()) }
        verify(exactly = 0) { childLinkService.link(any(), any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // findByIds
    // -------------------------------------------------------------------------

    "findByIds filters out removed by default" {
        val activeSkill = Skill(name = "Active", description = "D", body = "B", namespaceId = namespaceId)
        val removedSkill = Skill(name = "Removed", description = "D", body = "B", namespaceId = namespaceId)
            .let { it.copy(metadata = it.metadata.copy(removed = true)) }

        val activeNode = SkillNode.fromDomain(activeSkill)
        val removedNode = SkillNode.fromDomain(removedSkill)

        every { neo4jRepository.findAllById(listOf(activeSkill.id.toString(), removedSkill.id.toString())) } returns
            listOf(activeNode, removedNode)

        val result = repo.findByIds(listOf(activeSkill.id, removedSkill.id), withRemoved = false)

        result shouldHaveSize 1
        result.first().name shouldBe "Active"
    }

    "findByIds includes removed when withRemoved is true" {
        val activeSkill = Skill(name = "Active", description = "D", body = "B", namespaceId = namespaceId)
        val removedSkill = Skill(name = "Removed", description = "D", body = "B", namespaceId = namespaceId)
            .let { it.copy(metadata = it.metadata.copy(removed = true)) }

        val activeNode = SkillNode.fromDomain(activeSkill)
        val removedNode = SkillNode.fromDomain(removedSkill)

        every { neo4jRepository.findAllById(listOf(activeSkill.id.toString(), removedSkill.id.toString())) } returns
            listOf(activeNode, removedNode)

        val result = repo.findByIds(listOf(activeSkill.id, removedSkill.id), withRemoved = true)

        result shouldHaveSize 2
    }

    // -------------------------------------------------------------------------
    // findByNamespaceId and findByParent
    // -------------------------------------------------------------------------

    "findByNamespaceId and findByParent delegate to findActiveByNamespaceId" {
        val skill1 = Skill(name = "Skill 1", description = "D1", body = "B1", namespaceId = namespaceId)
        val skill2 = Skill(name = "Skill 2", description = "D2", body = "B2", namespaceId = namespaceId)

        every { neo4jRepository.findActiveByNamespaceId(namespaceId.toString()) } returns
            listOf(SkillNode.fromDomain(skill1), SkillNode.fromDomain(skill2))

        val byNs = repo.findByNamespaceId(namespaceId)
        val byParent = repo.findByParent(namespaceId)

        byNs shouldHaveSize 2
        byParent shouldHaveSize 2
        byNs.map { it.name } shouldBe listOf("Skill 1", "Skill 2")
    }

    // -------------------------------------------------------------------------
    // findPlatform
    // -------------------------------------------------------------------------

    "findPlatform delegates to findActivePlatform" {
        val platformSkill = Skill(name = "Platform", description = "D", body = "B", namespaceId = null)

        every { neo4jRepository.findActivePlatform() } returns listOf(SkillNode.fromDomain(platformSkill))

        val result = repo.findPlatform()

        result shouldHaveSize 1
        result.first().name shouldBe "Platform"
        result.first().namespaceId.shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // findByNameInNamespace
    // -------------------------------------------------------------------------

    "findByNameInNamespace computes lowercased doubleKey and queries findActiveByDoubleKey" {
        val skill = Skill(name = "Code Review", description = "D", body = "B", namespaceId = namespaceId)

        every { neo4jRepository.findActiveByDoubleKey("$namespaceId:code review") } returns
            SkillNode.fromDomain(skill)

        val found = repo.findByNameInNamespace(namespaceId, "CODE REVIEW")

        found.shouldNotBeNull()
        found.name shouldBe "Code Review"
    }

    "findByNameInNamespace supports platform scope with null namespaceId" {
        val platformSkill = Skill(name = "Global", description = "D", body = "B", namespaceId = null)

        every { neo4jRepository.findActiveByDoubleKey("_:global") } returns
            SkillNode.fromDomain(platformSkill)

        val found = repo.findByNameInNamespace(null, "Global")

        found.shouldNotBeNull()
        found.name shouldBe "Global"
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    "delete soft-deletes node and sets tombstone doubleKey" {
        val skill = Skill(name = "To Delete", description = "D", body = "B", namespaceId = namespaceId)
        val node = SkillNode.fromDomain(skill)

        every { neo4jRepository.findByIdOrNull(skill.id.toString()) } returns node
        val savedSlot = slot<SkillNode>()
        every { neo4jRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

        val deleted = repo.delete(skill.id)

        deleted shouldBe true
        savedSlot.captured.removed shouldBe true
        savedSlot.captured.doubleKey shouldBe "tombstone:${skill.id}"
    }

    "delete returns false when node does not exist or is already removed" {
        every { neo4jRepository.findByIdOrNull(any()) } returns null

        repo.delete(UUID.randomUUID()) shouldBe false
    }

    // -------------------------------------------------------------------------
    // deleteByParent
    // -------------------------------------------------------------------------

    "deleteByParent soft-deletes all active skills under namespace" {
        val skill1 = Skill(name = "S1", description = "D", body = "B", namespaceId = namespaceId)
        val skill2 = Skill(name = "S2", description = "D", body = "B", namespaceId = namespaceId)
        val nodes = listOf(SkillNode.fromDomain(skill1), SkillNode.fromDomain(skill2))

        every { neo4jRepository.findActiveByNamespaceId(namespaceId.toString()) } returns nodes
        val savedNodes = slot<Iterable<SkillNode>>()
        every { neo4jRepository.saveAll(capture(savedNodes)) } answers { savedNodes.captured.toList() }

        val count = repo.deleteByParent(namespaceId)

        count shouldBe 2
        savedNodes.captured.toList().forEach {
            it.removed shouldBe true
            it.doubleKey shouldBe "tombstone:${it.id}"
        }
    }
})
