package io.whozoss.agentos.aiModel

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for the `userId IS NULL` filter on [AiModelRepository.findByNamespaceId].
 * Story 6.4 AC14 (in-memory impl).
 */
class AiModelRepositoryNamespaceFilterSpec : StringSpec({

    fun repo() = InMemoryAiModelRepository()

    val providerId = UUID.randomUUID()
    val nsId = UUID.randomUUID()
    val userId = UUID.randomUUID()

    fun model(ns: UUID?, uid: UUID?, alias: String? = "default", apiName: String = "claude-haiku") =
        AiModel(
            metadata = EntityMetadata(),
            aiProviderId = providerId,
            namespaceId = ns,
            userId = uid,
            apiModelName = apiName,
            alias = alias,
        )

    "findByNamespaceId returns only namespace-shared models (userId IS NULL)" {
        val r = repo()
        val shared = model(nsId, null, alias = "default")
        val userScoped = model(nsId, userId, alias = "default", apiName = "claude-sonnet-override")
        r.save(shared)
        r.save(userScoped)

        val result = r.findByNamespaceId(nsId)

        result shouldHaveSize 1
        result.first().apiModelName shouldBe "claude-haiku"
    }

    "findByNamespaceId returns empty when namespace has only user-scoped models" {
        val r = repo()
        r.save(model(nsId, userId, alias = "default"))

        r.findByNamespaceId(nsId) shouldHaveSize 0
    }

    "findByUserId still returns user-scoped models regardless of namespace" {
        val r = repo()
        val shared = model(nsId, null, alias = "default")
        val userGlobal = model(null, userId, alias = "default")
        val userNs = model(nsId, userId, alias = "default", apiName = "claude-sonnet-override")
        r.save(shared)
        r.save(userGlobal)
        r.save(userNs)

        val result = r.findByUserId(userId)

        result shouldHaveSize 2
    }

    "findByTriple alias-first lookup is unaffected by namespace filter" {
        val r = repo()
        val shared = model(nsId, null, alias = "default")
        val userNs = model(nsId, userId, alias = "default", apiName = "claude-sonnet-override")
        r.save(shared)
        r.save(userNs)

        r.findByTriple(nsId, null, "default")?.apiModelName shouldBe "claude-haiku"
        r.findByTriple(nsId, userId, "default")?.apiModelName shouldBe "claude-sonnet-override"
    }

    "findByTriple fallback to apiModelName when alias is null" {
        val r = repo()
        val m = model(nsId, null, alias = null, apiName = "claude-haiku")
        r.save(m)

        r.findByTriple(nsId, null, "claude-haiku")?.apiModelName shouldBe "claude-haiku"
    }
})
