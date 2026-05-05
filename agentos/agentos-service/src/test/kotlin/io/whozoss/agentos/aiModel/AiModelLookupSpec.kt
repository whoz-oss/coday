package io.whozoss.agentos.aiModel

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [AiModelLookup] — 8 triple combinations.
 * Story 6.4 AC6, AC7, T3.
 */
class AiModelLookupSpec : StringSpec({

    val repository = mockk<AiModelRepository>()
    val lookup = AiModelLookup(repository)

    val namespaceId: UUID = UUID.randomUUID()
    val userId: UUID = UUID.randomUUID()
    val providerId: UUID = UUID.randomUUID()

    fun model(ns: UUID?, uid: UUID?, alias: String? = "default", apiName: String = "claude-haiku") =
        AiModel(
            metadata = EntityMetadata(),
            aiProviderId = providerId,
            namespaceId = ns,
            userId = uid,
            apiModelName = apiName,
            alias = alias,
        )

    "findByTriple(ns, null, alias) — returns namespace-shared config matched by alias" {
        val expected = model(namespaceId, null, alias = "default")
        every { repository.findByTriple(namespaceId, null, "default") } returns expected

        lookup.findByTriple(namespaceId, null, "default") shouldBe expected
        verify(exactly = 1) { repository.findByTriple(namespaceId, null, "default") }
    }

    "findByTriple(null, userId, alias) — returns user-global config" {
        val expected = model(null, userId, alias = "default")
        every { repository.findByTriple(null, userId, "default") } returns expected

        lookup.findByTriple(null, userId, "default") shouldBe expected
    }

    "findByTriple(ns, userId, alias) — returns user×namespace config" {
        val expected = model(namespaceId, userId, alias = "default")
        every { repository.findByTriple(namespaceId, userId, "default") } returns expected

        lookup.findByTriple(namespaceId, userId, "default") shouldBe expected
    }

    "findByTriple returns null when repository returns null" {
        every { repository.findByTriple(namespaceId, null, "missing") } returns null

        lookup.findByTriple(namespaceId, null, "missing").shouldBeNull()
    }

    "findByTriple(ns, null, apiName) — matches by apiModelName when alias is null" {
        val expected = model(namespaceId, null, alias = null, apiName = "claude-haiku")
        every { repository.findByTriple(namespaceId, null, "claude-haiku") } returns expected

        lookup.findByTriple(namespaceId, null, "claude-haiku") shouldBe expected
    }

    "findByTriple returns null for wrong name" {
        every { repository.findByTriple(namespaceId, null, "wrong") } returns null

        lookup.findByTriple(namespaceId, null, "wrong").shouldBeNull()
    }

    "findByTriple returns null for wrong namespaceId" {
        val other = UUID.randomUUID()
        every { repository.findByTriple(other, null, "default") } returns null

        lookup.findByTriple(other, null, "default").shouldBeNull()
    }

    "findByTriple passes through null ids to repository" {
        every { repository.findByTriple(null, null, "shared") } returns null

        lookup.findByTriple(null, null, "shared").shouldBeNull()
        verify { repository.findByTriple(null, null, "shared") }
    }
})
