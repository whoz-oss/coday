package io.whozoss.agentos.aiProvider

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for the `userId IS NULL` filter on [AiProviderRepository.findByNamespaceId].
 * Story 6.4 AC14 (in-memory impl).
 */
class AiProviderRepositoryNamespaceFilterSpec : StringSpec({

    fun repo() = InMemoryAiProviderRepository()

    fun provider(ns: UUID?, uid: UUID?, name: String) =
        AiProvider(
            metadata = EntityMetadata(),
            namespaceId = ns,
            userId = uid,
            name = name,
            apiType = AiApiType.Anthropic,
        )

    val nsId = UUID.randomUUID()
    val userId = UUID.randomUUID()

    "findByNamespaceId returns only namespace-shared providers (userId IS NULL)" {
        val r = repo()
        val shared = provider(nsId, null, "anthropic")
        val userScoped = provider(nsId, userId, "anthropic-override")
        r.save(shared)
        r.save(userScoped)

        val result = r.findByNamespaceId(nsId)

        result shouldHaveSize 1
        result.first().name shouldBe "anthropic"
    }

    "findByNamespaceId returns empty when namespace has only user-scoped providers" {
        val r = repo()
        r.save(provider(nsId, userId, "anthropic-override"))

        r.findByNamespaceId(nsId) shouldHaveSize 0
    }

    "findByUserId still returns user-scoped providers regardless of namespace" {
        val r = repo()
        val shared = provider(nsId, null, "anthropic")
        val userGlobal = provider(null, userId, "openai")
        val userNs = provider(nsId, userId, "anthropic-override")
        r.save(shared)
        r.save(userGlobal)
        r.save(userNs)

        val result = r.findByUserId(userId)

        result shouldHaveSize 2
        result.map { it.name }.toSet() shouldBe setOf("openai", "anthropic-override")
    }

    "findByTriple is unaffected by the namespace filter change" {
        val r = repo()
        val shared = provider(nsId, null, "anthropic")
        val userNs = provider(nsId, userId, "anthropic")
        r.save(shared)
        r.save(userNs)

        r.findByTriple(nsId, null, "anthropic")?.name shouldBe "anthropic"
        r.findByTriple(nsId, userId, "anthropic")?.name shouldBe "anthropic"
    }
})
