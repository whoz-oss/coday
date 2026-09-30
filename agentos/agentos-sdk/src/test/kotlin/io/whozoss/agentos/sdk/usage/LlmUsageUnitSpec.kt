package io.whozoss.agentos.sdk.usage

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Unit tests for [LlmUsage] semantics, focusing on the [LlmUsage.plus] operator
 * and the [LlmUsage.ZERO] neutral element.
 */
class LlmUsageUnitSpec : StringSpec({

    // -------------------------------------------------------------------------
    // ZERO neutral element
    // -------------------------------------------------------------------------

    "ZERO has estimatedCostUsd = 0.0 so it is neutral for addition" {
        LlmUsage.ZERO.estimatedCostUsd shouldBe 0.0
    }

    "fold with ZERO preserves a known cost when all elements are priced" {
        val usages = listOf(
            LlmUsage(totalTokens = 100L, estimatedCostUsd = 0.25),
            LlmUsage(totalTokens = 200L, estimatedCostUsd = 0.50),
        )
        val total = usages.fold(LlmUsage.ZERO) { acc, u -> acc + u }
        total.totalTokens shouldBe 300L
        total.estimatedCostUsd shouldBe 0.75
    }

    "fold with ZERO returns null cost when any element is unpriced" {
        val usages = listOf(
            LlmUsage(totalTokens = 100L, estimatedCostUsd = 0.25),
            LlmUsage(totalTokens = 200L, estimatedCostUsd = null),
        )
        val total = usages.fold(LlmUsage.ZERO) { acc, u -> acc + u }
        total.totalTokens shouldBe 300L
        total.estimatedCostUsd shouldBe null
    }

    // -------------------------------------------------------------------------
    // plus operator
    // -------------------------------------------------------------------------

    "plus sums all token counts" {
        val a = LlmUsage(inputTokens = 10L, outputTokens = 20L, cacheReadTokens = 5L, cacheWriteTokens = 2L, totalTokens = 37L)
        val b = LlmUsage(inputTokens = 100L, outputTokens = 200L, cacheReadTokens = 50L, cacheWriteTokens = 20L, totalTokens = 370L)
        val sum = a + b
        sum.inputTokens shouldBe 110L
        sum.outputTokens shouldBe 220L
        sum.cacheReadTokens shouldBe 55L
        sum.cacheWriteTokens shouldBe 22L
        sum.totalTokens shouldBe 407L
    }

    "plus sums costs when both are non-null" {
        val a = LlmUsage(estimatedCostUsd = 0.25)
        val b = LlmUsage(estimatedCostUsd = 0.50)
        (a + b).estimatedCostUsd shouldBe 0.75
    }

    "plus returns null cost when left side is null" {
        val a = LlmUsage(estimatedCostUsd = null)
        val b = LlmUsage(estimatedCostUsd = 0.50)
        (a + b).estimatedCostUsd shouldBe null
    }

    "plus returns null cost when right side is null" {
        val a = LlmUsage(estimatedCostUsd = 0.25)
        val b = LlmUsage(estimatedCostUsd = null)
        (a + b).estimatedCostUsd shouldBe null
    }

    "plus returns null cost when both sides are null" {
        val a = LlmUsage(estimatedCostUsd = null)
        val b = LlmUsage(estimatedCostUsd = null)
        (a + b).estimatedCostUsd shouldBe null
    }
})
