package io.whozoss.agentos.skill

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Instant
import java.util.UUID

class SkillNodeUnitSpec : StringSpec({

    val nsId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    // -------------------------------------------------------------------------
    // computeDoubleKey
    // -------------------------------------------------------------------------

    "computeDoubleKey with platform scope encodes NULL_ID_SENTINEL and lowercased name" {
        SkillNode.computeDoubleKey(null, "Branch Creation") shouldBe "_:branch creation"
    }

    "computeDoubleKey with same name different casing produces identical key" {
        val key1 = SkillNode.computeDoubleKey(nsId, "Code Review")
        val key2 = SkillNode.computeDoubleKey(nsId, "code review")
        val key3 = SkillNode.computeDoubleKey(nsId, "CODE REVIEW")
        key1 shouldBe key2
        key1 shouldBe key3
        key1 shouldBe "$nsId:code review"
    }

    "computeDoubleKey with different namespaces produces distinct keys" {
        val ns2 = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val k1 = SkillNode.computeDoubleKey(nsId, "Spec Writing")
        val k2 = SkillNode.computeDoubleKey(ns2, "Spec Writing")
        (k1 == k2) shouldBe false
    }

    "tombstoneDoubleKey embeds id and starts with tombstone:" {
        val id = UUID.randomUUID().toString()
        SkillNode.tombstoneDoubleKey(id) shouldBe "tombstone:$id"
    }

    // -------------------------------------------------------------------------
    // fromDomain and toDomain round-trip
    // -------------------------------------------------------------------------

    "fromDomain and toDomain preserve all entity metadata and domain fields" {
        val id = UUID.randomUUID()
        val created = Instant.parse("2025-01-01T10:00:00Z")
        val modified = Instant.parse("2025-01-02T12:00:00Z")
        val skill = Skill(
            metadata = EntityMetadata(
                id = id,
                created = created,
                createdBy = "alice",
                modified = modified,
                modifiedBy = "bob",
                removed = false,
                version = 1L,
            ),
            namespaceId = nsId,
            name = "Code Review",
            description = "Reviews PRs",
            body = "## Instructions\nReview carefully.",
            skillRelativePath = "core/code-review",
            resourceRoot = "/tmp/skills/core/code-review",
        )

        val node = SkillNode.fromDomain(skill)
        node.id shouldBe id.toString()
        node.namespaceId shouldBe nsId.toString()
        node.name shouldBe "Code Review"
        node.doubleKey shouldBe "$nsId:code review"
        node.description shouldBe "Reviews PRs"
        node.body shouldBe "## Instructions\nReview carefully."
        node.version shouldBe 1L
        node.created shouldBe created
        node.createdBy shouldBe "alice"
        node.modified shouldBe modified
        node.modifiedBy shouldBe "bob"
        node.removed.shouldBeNull()

        val roundTripped = node.toDomain()
        roundTripped.id shouldBe id
        roundTripped.namespaceId shouldBe nsId
        roundTripped.name shouldBe "Code Review"
        roundTripped.description shouldBe "Reviews PRs"
        roundTripped.body shouldBe "## Instructions\nReview carefully."
        roundTripped.metadata.created shouldBe created
        roundTripped.metadata.createdBy shouldBe "alice"
        roundTripped.metadata.modified shouldBe modified
        roundTripped.metadata.modifiedBy shouldBe "bob"
        roundTripped.metadata.removed shouldBe false
        roundTripped.metadata.version shouldBe 1L
        // Storage asymmetry: skillRelativePath and resourceRoot are null when coming from DB node
        roundTripped.skillRelativePath.shouldBeNull()
        roundTripped.resourceRoot.shouldBeNull()
    }

    "fromDomain sets tombstone key when removed is true" {
        val id = UUID.randomUUID()
        val skill = Skill(
            metadata = EntityMetadata(id = id, removed = true),
            namespaceId = nsId,
            name = "Deleted Skill",
            description = "Desc",
            body = "Body",
        )

        val node = SkillNode.fromDomain(skill)
        node.doubleKey shouldBe "tombstone:$id"
        node.removed shouldBe true

        val domain = node.toDomain()
        domain.metadata.removed shouldBe true
    }

    "fromDomain sets namespaceId to null for platform skills" {
        val skill = Skill(
            metadata = EntityMetadata(),
            namespaceId = null,
            name = "Platform Skill",
            description = "Global skill",
            body = "Global body",
        )

        val node = SkillNode.fromDomain(skill)
        node.namespaceId.shouldBeNull()
        node.doubleKey shouldBe "_:platform skill"

        val domain = node.toDomain()
        domain.namespaceId.shouldBeNull()
    }
})
