package io.whozoss.agentos.aiModel

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [AiModelMergeStrategy] — 8 overlap cases + alias-fallback (AC7) + priority semantics.
 * Story 6.4 AC8, T7.
 */
class AiModelMergeStrategySpec : StringSpec({

    val strategy = AiModelMergeStrategy()

    val providerId = UUID.randomUUID()
    val nsId = UUID.randomUUID()
    val userId = UUID.randomUUID()

    fun model(
        ns: UUID? = nsId,
        uid: UUID? = null,
        apiName: String = "claude-haiku",
        alias: String? = "default",
        temperature: Double? = null,
        maxTokens: Int? = null,
        priority: Int = 0,
        description: String? = null,
        provider: UUID = providerId,
    ) = AiModel(
        metadata = EntityMetadata(),
        aiProviderId = provider,
        namespaceId = ns,
        userId = uid,
        apiModelName = apiName,
        alias = alias,
        temperature = temperature,
        maxTokens = maxTokens,
        priority = priority,
        description = description,
    )

    // -------------------------------------------------------------------------
    // Identity preservation
    // -------------------------------------------------------------------------

    "merge preserves base identity fields (id, metadata, namespaceId, userId, aiProviderId, alias)" {
        val base = model(ns = nsId, uid = null, alias = "default", provider = providerId)
        val override = model(ns = null, uid = userId, alias = "other-alias")

        val merged = strategy.merge(base, override)

        merged.metadata.id shouldBe base.metadata.id
        merged.namespaceId shouldBe base.namespaceId
        merged.userId shouldBe base.userId
        merged.aiProviderId shouldBe base.aiProviderId
        merged.alias shouldBe base.alias
    }

    // -------------------------------------------------------------------------
    // Field-by-field override semantics (8 overlap cases)
    // -------------------------------------------------------------------------

    "merge override wins on apiModelName when non-blank" {
        val base = model(apiName = "claude-haiku")
        val override = model(apiName = "claude-sonnet-4-5")

        strategy.merge(base, override).apiModelName shouldBe "claude-sonnet-4-5"
    }

    "merge preserves base apiModelName when override is blank" {
        val base = model(apiName = "claude-haiku")
        val override = model(apiName = "")

        strategy.merge(base, override).apiModelName shouldBe "claude-haiku"
    }

    "merge override wins on temperature when non-null" {
        val base = model(temperature = 0.5)
        val override = model(temperature = 0.9)

        strategy.merge(base, override).temperature shouldBe 0.9
    }

    "merge preserves base temperature when override is null" {
        val base = model(temperature = 0.5)
        val override = model(temperature = null)

        strategy.merge(base, override).temperature shouldBe 0.5
    }

    "merge override wins on maxTokens when non-null" {
        val base = model(maxTokens = 1000)
        val override = model(maxTokens = 2000)

        strategy.merge(base, override).maxTokens shouldBe 2000
    }

    "merge preserves base maxTokens when override is null" {
        val base = model(maxTokens = 1000)
        val override = model(maxTokens = null)

        strategy.merge(base, override).maxTokens shouldBe 1000
    }

    "merge override wins on description when non-null" {
        val base = model(description = "base desc")
        val override = model(description = "override desc")

        strategy.merge(base, override).description shouldBe "override desc"
    }

    "merge preserves base description when override is null" {
        val base = model(description = "base desc")
        val override = model(description = null)

        strategy.merge(base, override).description shouldBe "base desc"
    }

    // -------------------------------------------------------------------------
    // Priority semantics — 0 = unset (AC8 Dev Notes)
    // -------------------------------------------------------------------------

    "merge override wins on priority when non-zero" {
        val base = model(priority = 5)
        val override = model(priority = 10)

        strategy.merge(base, override).priority shouldBe 10
    }

    "merge preserves base priority when override is zero (treated as unset)" {
        val base = model(priority = 5)
        val override = model(priority = 0)

        strategy.merge(base, override).priority shouldBe 5
    }

    "merge yields zero when base priority is zero and override is zero" {
        strategy.merge(model(priority = 0), model(priority = 0)).priority shouldBe 0
    }

    // -------------------------------------------------------------------------
    // alias-fallback combinations (AC7)
    // -------------------------------------------------------------------------

    "alias preserved from base even when override carries a different alias" {
        val base = model(alias = "default")
        val override = model(alias = "other")

        strategy.merge(base, override).alias shouldBe "default"
    }

    "alias preserved from base when both have null alias" {
        val base = model(alias = null)
        val override = model(alias = null)

        strategy.merge(base, override).alias shouldBe null
    }

    // -------------------------------------------------------------------------
    // 3-tier fold correctness
    // -------------------------------------------------------------------------

    "three-layer fold produces correct precedence" {
        val ns = model(apiName = "claude-haiku", temperature = 0.3, maxTokens = 500)
        val global = model(apiName = "claude-haiku", temperature = 0.7, maxTokens = null)
        val userNs = model(apiName = "claude-haiku", temperature = null, maxTokens = 2000)

        val afterGlobal = strategy.merge(ns, global)
        val final = strategy.merge(afterGlobal, userNs)

        final.temperature shouldBe 0.7
        final.maxTokens shouldBe 2000
    }

    "merge result identity is from base layer" {
        val base = model(ns = nsId, uid = null)
        val override = model(ns = null, uid = userId)

        val merged = strategy.merge(base, override)
        merged.metadata.id shouldBe base.metadata.id
        merged.metadata.id shouldNotBe override.metadata.id
    }
})
