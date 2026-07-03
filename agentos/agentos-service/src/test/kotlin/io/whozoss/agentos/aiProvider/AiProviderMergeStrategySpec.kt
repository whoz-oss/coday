package io.whozoss.agentos.aiProvider

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [AiProviderMergeStrategy] — 8 overlap cases + 4 dedicated apiKey tests.
 * Story 6.4 AC8, T6.
 */
class AiProviderMergeStrategySpec : StringSpec({

    val strategy = AiProviderMergeStrategy()

    val nsId = UUID.randomUUID()
    val userId = UUID.randomUUID()

    fun provider(
        ns: UUID? = nsId,
        uid: UUID? = null,
        name: String = "anthropic",
        apiKey: String? = "base-key",
        baseUrl: String? = "https://base.url",
        description: String? = "base desc",
        apiType: AiApiType = AiApiType.Anthropic,
    ) = AiProvider(
        metadata = EntityMetadata(),
        namespaceId = ns,
        userId = uid,
        name = name,
        apiKey = apiKey,
        baseUrl = baseUrl,
        description = description,
        apiType = apiType,
    )

    // -------------------------------------------------------------------------
    // Identity preservation
    // -------------------------------------------------------------------------

    "merge preserves base identity fields (id, metadata, namespaceId, userId, name)" {
        val base = provider(ns = nsId, uid = null, name = "anthropic")
        val override = provider(ns = null, uid = userId, name = "override-name", apiKey = "new-key")

        val merged = strategy.merge(base, override)

        merged.metadata.id shouldBe base.metadata.id
        merged.namespaceId shouldBe base.namespaceId
        merged.userId shouldBe base.userId
        merged.name shouldBe base.name
    }

    // -------------------------------------------------------------------------
    // Field-by-field override semantics (8 overlap cases)
    // -------------------------------------------------------------------------

    "merge override wins on apiKey when non-null and non-blank" {
        val base = provider(apiKey = "base-key")
        val override = provider(apiKey = "override-key")

        strategy.merge(base, override).apiKey shouldBe "override-key"
    }

    "merge override wins on baseUrl when non-null and non-blank" {
        val base = provider(baseUrl = "https://base.url")
        val override = provider(baseUrl = "https://override.url")

        strategy.merge(base, override).baseUrl shouldBe "https://override.url"
    }

    "merge override wins on description when non-null" {
        val base = provider(description = "base desc")
        val override = provider(description = "override desc")

        strategy.merge(base, override).description shouldBe "override desc"
    }

    "merge override wins on apiType" {
        val base = provider(apiType = AiApiType.Anthropic)
        val override = provider(apiType = AiApiType.OpenAI)

        strategy.merge(base, override).apiType shouldBe AiApiType.OpenAI
    }

    "merge preserves base apiKey when override apiKey is null" {
        val base = provider(apiKey = "base-key")
        val override = provider(apiKey = null)

        strategy.merge(base, override).apiKey shouldBe "base-key"
    }

    "merge preserves base apiKey when override apiKey is blank" {
        val base = provider(apiKey = "base-key")
        val override = provider(apiKey = "")

        strategy.merge(base, override).apiKey shouldBe "base-key"
    }

    "merge preserves base baseUrl when override baseUrl is null" {
        val base = provider(baseUrl = "https://base.url")
        val override = provider(baseUrl = null)

        strategy.merge(base, override).baseUrl shouldBe "https://base.url"
    }

    "merge preserves base description when override description is null" {
        val base = provider(description = "base desc")
        val override = provider(description = null)

        strategy.merge(base, override).description shouldBe "base desc"
    }

    // -------------------------------------------------------------------------
    // Dedicated apiKey blank-preservation tests (AC8 last requirement)
    // -------------------------------------------------------------------------

    "apiKey blank preservation — override null" {
        strategy.merge(provider(apiKey = "valid-key"), provider(apiKey = null)).apiKey shouldBe "valid-key"
    }

    "apiKey blank preservation — override blank string" {
        strategy.merge(provider(apiKey = "valid-key"), provider(apiKey = "")).apiKey shouldBe "valid-key"
    }

    "apiKey override wins — valid override key" {
        strategy.merge(provider(apiKey = "old-key"), provider(apiKey = "new-key")).apiKey shouldBe "new-key"
    }

    "apiKey — base null and override null stays null" {
        strategy.merge(provider(apiKey = null), provider(apiKey = null)).apiKey shouldBe null
    }

    // -------------------------------------------------------------------------
    // Full 3-tier fold (namespace → user-global → user×namespace)
    // -------------------------------------------------------------------------

    "three-layer fold produces correct precedence" {
        val ns = provider(apiKey = "ns-key", baseUrl = "https://ns.url", description = "ns desc")
        val global = provider(apiKey = "global-key", baseUrl = null, description = null)
        val userNs = provider(apiKey = null, baseUrl = null, description = "user-ns desc")

        val afterGlobal = strategy.merge(ns, global)
        val final = strategy.merge(afterGlobal, userNs)

        final.apiKey shouldBe "global-key"
        final.baseUrl shouldBe "https://ns.url"
        final.description shouldBe "user-ns desc"
    }

    "merge result identity is from base (lower layer)" {
        val base = provider(ns = nsId, uid = null)
        val override = provider(ns = null, uid = userId)

        val merged = strategy.merge(base, override)
        merged.metadata.id shouldBe base.metadata.id
        merged.metadata.id shouldNotBe override.metadata.id
    }
})
