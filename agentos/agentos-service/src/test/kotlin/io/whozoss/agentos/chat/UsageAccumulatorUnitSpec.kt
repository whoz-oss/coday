package io.whozoss.agentos.chat

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.usage.LlmUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Unit tests for [UsageAccumulator].
 *
 * ## Null cost semantics
 *
 * `estimatedCostUsd = null` means "pricing not configured for this model".
 * The accumulator uses a sentinel UNSET state to distinguish "no entry yet" from
 * "at least one unpriced entry recorded":
 *
 * - Before any [UsageAccumulator.record] call: total cost is null (UNSET → null).
 * - First entry with non-null cost seeds the running total.
 * - Any subsequent entry with null cost contaminates the total permanently.
 * - First entry with null cost keeps the total null from the start.
 *
 * This means a mixed run (some priced models, some unpriced) produces null cost,
 * which is honest: the aggregate would otherwise be a silent undercount.
 */
class UsageAccumulatorUnitSpec : StringSpec({

    "initial total is LlmUsage.ZERO" {
        UsageAccumulator().total shouldBe LlmUsage.ZERO
    }

    "record accumulates a single entry" {
        val acc = UsageAccumulator()
        acc.record(LlmUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150))

        acc.total.inputTokens shouldBe 100L
        acc.total.outputTokens shouldBe 50L
        acc.total.totalTokens shouldBe 150L
    }

    "record accumulates multiple sequential entries" {
        val acc = UsageAccumulator()
        acc.record(LlmUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150, estimatedCostUsd = 0.001))
        acc.record(LlmUsage(inputTokens = 200, outputTokens = 80, totalTokens = 280, estimatedCostUsd = 0.002))

        acc.total.inputTokens shouldBe 300L
        acc.total.outputTokens shouldBe 130L
        acc.total.totalTokens shouldBe 430L
        acc.total.estimatedCostUsd shouldBe 0.003
    }

    "recording LlmUsage.ZERO does not change the total" {
        val acc = UsageAccumulator()
        acc.record(LlmUsage(inputTokens = 100, totalTokens = 100))
        acc.record(LlmUsage.ZERO)

        acc.total.inputTokens shouldBe 100L
    }

    "null cost on the first entry: total cost is null" {
        // An unpriced model as the very first entry keeps cost null.
        val acc = UsageAccumulator()
        acc.record(LlmUsage(inputTokens = 100, estimatedCostUsd = null))

        acc.total.estimatedCostUsd shouldBe null
    }

    "null cost after a priced entry contaminates the total" {
        // A priced model followed by an unpriced model: the aggregate is an undercount
        // and must be reported as null, not as the partial priced sum.
        val acc = UsageAccumulator()
        acc.record(LlmUsage(inputTokens = 100, estimatedCostUsd = 0.001))
        acc.record(LlmUsage(inputTokens = 200, estimatedCostUsd = null))

        acc.total.estimatedCostUsd shouldBe null
    }

    "priced entry after an unpriced entry keeps cost null" {
        // Once contaminated, the total cost stays null regardless of subsequent entries.
        val acc = UsageAccumulator()
        acc.record(LlmUsage(inputTokens = 100, estimatedCostUsd = null))
        acc.record(LlmUsage(inputTokens = 200, estimatedCostUsd = 0.002))

        acc.total.estimatedCostUsd shouldBe null
    }

    "concurrent records produce the correct sum (CAS thread-safety)" {
        val acc = UsageAccumulator()
        val threads = 100
        val tokensPerThread = 10L

        runBlocking {
            val jobs =
                (1..threads).map {
                    launch(Dispatchers.Default) {
                        acc.record(LlmUsage(inputTokens = tokensPerThread, totalTokens = tokensPerThread))
                    }
                }
            jobs.forEach { it.join() }
        }

        acc.total.inputTokens shouldBe threads * tokensPerThread
        acc.total.totalTokens shouldBe threads * tokensPerThread
    }
})
