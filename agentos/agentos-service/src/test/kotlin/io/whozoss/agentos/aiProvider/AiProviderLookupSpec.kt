package io.whozoss.agentos.aiProvider

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [AiProviderLookup] — 8 triple combinations (presence/absence of nullable ids,
 * match/no-match on name). Story 6.4 AC5, T2.
 */
class AiProviderLookupSpec : StringSpec({

    val repository = mockk<AiProviderRepository>()
    val lookup = AiProviderLookup(repository)

    val namespaceId: UUID = UUID.randomUUID()
    val userId: UUID = UUID.randomUUID()

    fun provider(ns: UUID?, uid: UUID?, name: String) =
        AiProvider(
            metadata = EntityMetadata(),
            namespaceId = ns,
            userId = uid,
            name = name,
            apiType = AiApiType.Anthropic,
        )

    "findByTriple(ns, null, name) — delegates to repository and returns namespace-shared config" {
        val expected = provider(namespaceId, null, "anthropic")
        every { repository.findByTriple(namespaceId, null, "anthropic") } returns expected

        lookup.findByTriple(namespaceId, null, "anthropic") shouldBe expected
        verify(exactly = 1) { repository.findByTriple(namespaceId, null, "anthropic") }
    }

    "findByTriple(null, userId, name) — delegates to repository and returns user-global config" {
        val expected = provider(null, userId, "anthropic")
        every { repository.findByTriple(null, userId, "anthropic") } returns expected

        lookup.findByTriple(null, userId, "anthropic") shouldBe expected
    }

    "findByTriple(ns, userId, name) — delegates to repository and returns user×namespace config" {
        val expected = provider(namespaceId, userId, "anthropic")
        every { repository.findByTriple(namespaceId, userId, "anthropic") } returns expected

        lookup.findByTriple(namespaceId, userId, "anthropic") shouldBe expected
    }

    "findByTriple returns null when repository returns null" {
        every { repository.findByTriple(namespaceId, null, "missing") } returns null

        lookup.findByTriple(namespaceId, null, "missing").shouldBeNull()
    }

    "findByTriple(ns, null, wrongName) — returns null when name does not match" {
        every { repository.findByTriple(namespaceId, null, "wrong") } returns null

        lookup.findByTriple(namespaceId, null, "wrong").shouldBeNull()
    }

    "findByTriple(otherNs, null, name) — returns null when namespaceId does not match" {
        val otherNs = UUID.randomUUID()
        every { repository.findByTriple(otherNs, null, "anthropic") } returns null

        lookup.findByTriple(otherNs, null, "anthropic").shouldBeNull()
    }

    "findByTriple(null, otherUser, name) — returns null when userId does not match" {
        val otherUser = UUID.randomUUID()
        every { repository.findByTriple(null, otherUser, "anthropic") } returns null

        lookup.findByTriple(null, otherUser, "anthropic").shouldBeNull()
    }

    "findByTriple passes through null ids without substitution" {
        every { repository.findByTriple(null, null, "shared") } returns null

        lookup.findByTriple(null, null, "shared").shouldBeNull()
        verify { repository.findByTriple(null, null, "shared") }
    }
})
