package io.whozoss.agentos.chat

import io.whozoss.agentos.sdk.usage.LlmUsage
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe accumulator for [LlmUsage] across multiple LLM calls within a case.
 *
 * ## Lifecycle
 *
 * One [UsageAccumulator] is created per agent run (via [ChatClientProvider]) and
 * shared between all [UsageTrackingChatClient] calls within that run. At the end of
 * the run, the accumulated totals are retrieved via [total] and attached to
 * [io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent.llmUsage].
 *
 * ## Null cost semantics
 *
 * `estimatedCostUsd = null` on a [LlmUsage] means "pricing not configured for this
 * model" — it is not the same as zero cost. The accumulator treats `null` as
 * **contaminating**: once any recorded entry has a null cost, the running total cost
 * becomes null and stays null, because the aggregate would otherwise be a silent
 * undercount (some calls are unpriced).
 *
 * [LlmUsage.ZERO] (the accumulator's initial state) has `estimatedCostUsd = null`.
 * To avoid the initial null contaminating the first real entry, the accumulator tracks
 * whether any entry with a non-null cost has ever been recorded. The first non-null cost
 * seeds the running total; subsequent null costs then taint it.
 *
 * Concretely: the running cost is `null` until the first `record()` call with a
 * non-null cost, at which point it becomes the cost of that call. Any later `record()`
 * with a null cost resets it to null permanently.
 *
 * ## Thread safety
 *
 * Token counts use [AtomicLong] for lock-free summation.
 * The cost uses [AtomicReference] with compare-and-set to merge atomically.
 */
class UsageAccumulator {
    private val inputTokens = AtomicLong(0L)
    private val outputTokens = AtomicLong(0L)
    private val cacheReadTokens = AtomicLong(0L)
    private val cacheWriteTokens = AtomicLong(0L)
    private val totalTokens = AtomicLong(0L)

    /**
     * Sentinel that distinguishes "no entry yet" (UNSET) from "at least one null-cost
     * entry recorded" (null). Once UNSET is replaced by a real value or by null, it
     * never reverts to UNSET.
     */
    private val costRef = AtomicReference<Any?>(UNSET)

    /**
     * Merges [usage] into the running total.
     * Thread-safe: token counts via [AtomicLong.addAndGet], cost via CAS loop.
     *
     * A [LlmUsage.ZERO]-equivalent entry (all token counts zero, null cost) is silently
     * ignored so that [hasData] stays false after a provider returns an empty usage block.
     * This preserves the invariant: "no LLM call was made" ↔ `hasData == false`.
     * A non-zero token count with null cost (e.g. Gemini/Ollama without pricing configured)
     * is recorded normally and contaminates the cost total.
     */
    fun record(usage: LlmUsage) {
        if (usage.totalTokens == 0L &&
            usage.inputTokens == 0L &&
            usage.outputTokens == 0L &&
            usage.cacheReadTokens == 0L &&
            usage.cacheWriteTokens == 0L &&
            usage.estimatedCostUsd == null
        ) return

        inputTokens.addAndGet(usage.inputTokens)
        outputTokens.addAndGet(usage.outputTokens)
        cacheReadTokens.addAndGet(usage.cacheReadTokens)
        cacheWriteTokens.addAndGet(usage.cacheWriteTokens)
        totalTokens.addAndGet(usage.totalTokens)

        // CAS loop: merge the incoming cost into the running cost.
        // - UNSET + non-null  -> non-null  (seed)
        // - UNSET + null      -> null      (first entry is already unpriced)
        // - non-null + non-null -> sum
        // - non-null + null   -> null      (contaminate)
        // - null + anything   -> null      (stays contaminated)
        val incoming = usage.estimatedCostUsd
        costRef.updateAndGet { current ->
            when {
                current === null -> null               // already contaminated
                current === UNSET -> incoming          // seed (may be null or a Double)
                incoming == null -> null               // contaminate
                else -> (current as Double) + incoming // both known
            }
        }
    }

    /**
     * Returns the current accumulated total.
     * The snapshot is consistent but may be stale if [record] is called concurrently.
     */
    val total: LlmUsage
        get() {
            val cost = costRef.get().let { if (it === UNSET) null else it as Double? }
            return LlmUsage(
                inputTokens = inputTokens.get(),
                outputTokens = outputTokens.get(),
                cacheReadTokens = cacheReadTokens.get(),
                cacheWriteTokens = cacheWriteTokens.get(),
                totalTokens = totalTokens.get(),
                estimatedCostUsd = cost,
            )
        }

    /**
     * Returns `true` when at least one call has been recorded (total is non-zero).
     */
    val hasData: Boolean
        get() = totalTokens.get() != 0L || inputTokens.get() != 0L || costRef.get() !== UNSET

    private companion object {
        /** Sentinel value meaning "no record() call has been made yet". */
        val UNSET = Any()
    }
}
