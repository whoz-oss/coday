package io.whozoss.agentos.chat

import io.whozoss.agentos.sdk.usage.LlmUsage
import java.util.concurrent.CompletableFuture

/** One coherent snapshot per agent invocation, shared by all of its LLM calls. */
class UsageAccumulator {
    private var accumulated = LlmUsage.ZERO
    private var calls = 0L
    private var knownCost = 0.0
    private var unknownCalls = 0L
    private var priced = LlmUsage.ZERO
    private var unpriced = LlmUsage.ZERO

    @Volatile var failed = false

    /** Gate evaluated before each request. Completing it resumes the same request/context. */
    var beforeCall: () -> CompletableFuture<Void> = { CompletableFuture.completedFuture(null) }

    @Synchronized
    fun record(usage: LlmUsage) {
        if (usage.totalTokens == 0L && usage.inputTokens == 0L && usage.outputTokens == 0L &&
            usage.cacheReadTokens == 0L && usage.cacheWriteTokens == 0L &&
            (usage.estimatedCostUsd == null || usage.estimatedCostUsd == 0.0)
        ) {
            return
        }
        accumulated += usage
        calls++
        usage.estimatedCostUsd?.let {
            knownCost += it
            priced += usage
        } ?: run {
            unknownCalls++
            unpriced += usage
        }
    }

    @get:Synchronized
    val total: LlmUsage get() = accumulated

    @get:Synchronized
    val hasData: Boolean get() = calls > 0

    /** Separate priced/unpriced facts preserve known costs after a mixed run finishes. */
    @Synchronized
    fun recordGroups(): List<LlmUsage> = listOf(priced, unpriced).filter { it != LlmUsage.ZERO }

    @Synchronized
    fun snapshot() = Snapshot(accumulated, calls, knownCost, unknownCalls)

    data class Snapshot(
        val usage: LlmUsage,
        val calls: Long,
        val knownCost: Double,
        val unknownCalls: Long,
    )
}
