package io.whozoss.agentos.chat

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
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

        "without the one-hour markers: the standard lifetime of the model" {
            PromptCacheLifetime.of(model(cacheTtlMinutes = 30, extendedCacheTtlMinutes = 90), false) shouldBe Duration.ofMinutes(30)
            PromptCacheLifetime.of(model(extendedCacheTtlMinutes = 90), false).shouldBeNull()
        }

        "with the one-hour markers: the extended lifetime of the model, never the standard one" {
            PromptCacheLifetime.of(model(cacheTtlMinutes = 5, extendedCacheTtlMinutes = 60), true) shouldBe Duration.ofMinutes(60)
            PromptCacheLifetime.of(model(cacheTtlMinutes = 5), true).shouldBeNull()
        }

        "a missing model or a lifetime that is not positive gives no lifetime" {
            PromptCacheLifetime.of(null, false).shouldBeNull()
            PromptCacheLifetime.of(model(cacheTtlMinutes = 0), false).shouldBeNull()
            PromptCacheLifetime.of(model(extendedCacheTtlMinutes = -1), true).shouldBeNull()
        }
    })
