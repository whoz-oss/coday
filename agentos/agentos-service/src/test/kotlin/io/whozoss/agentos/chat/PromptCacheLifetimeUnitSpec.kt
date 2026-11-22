package io.whozoss.agentos.chat

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.aiProvider.AiModel
import java.time.Duration
import java.util.UUID

class PromptCacheLifetimeUnitSpec :
    StringSpec({
        fun model(
            cacheTtlMinutes: Int? = null,
            extendedCacheTtlMinutes: Int? = null,
        ) = AiModel(
            aiProviderId = UUID.randomUUID(),
            apiModelName = "m",
            cacheTtlMinutes = cacheTtlMinutes,
            extendedCacheTtlMinutes = extendedCacheTtlMinutes,
        )

        "without the one-hour markers: the standard lifetime of the model, else 5 minutes" {
            PromptCacheLifetime.of(model(cacheTtlMinutes = 30, extendedCacheTtlMinutes = 90), false) shouldBe Duration.ofMinutes(30)
            PromptCacheLifetime.of(model(extendedCacheTtlMinutes = 90), false) shouldBe Duration.ofMinutes(5)
        }

        "with the one-hour markers: the extended lifetime of the model, else 60 minutes, never the standard one" {
            PromptCacheLifetime.of(model(cacheTtlMinutes = 5, extendedCacheTtlMinutes = 90), true) shouldBe Duration.ofMinutes(90)
            PromptCacheLifetime.of(model(cacheTtlMinutes = 5), true) shouldBe Duration.ofMinutes(60)
        }

        "a missing model or a lifetime that is not positive falls back to the defaults" {
            PromptCacheLifetime.of(null, false) shouldBe Duration.ofMinutes(5)
            PromptCacheLifetime.of(null, true) shouldBe Duration.ofMinutes(60)
            PromptCacheLifetime.of(model(cacheTtlMinutes = 0, extendedCacheTtlMinutes = -1), false) shouldBe Duration.ofMinutes(5)
            PromptCacheLifetime.of(model(cacheTtlMinutes = 0, extendedCacheTtlMinutes = -1), true) shouldBe Duration.ofMinutes(60)
        }
    })
