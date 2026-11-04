package io.whozoss.agentos.compaction

import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The context budget of ONE agent invocation: how much of the model's window its prompts may use, and how
 * big a given prompt is. See ADR 002 (Phase 1). It only measures; nothing here changes what is sent.
 *
 * ## Budget
 * `usableTokens = contextWindow - (maxCompletionTokens ?: 0)`. A null [contextWindow] disables the budget:
 * it is never reached and nothing is logged on its behalf ("no proactive compaction, never a guess"). So
 * does a window not larger than the completion allowance, which is a misconfiguration no ratio can fix.
 * [triggerTokens] is `threshold x usable` (the budget counts as reached from there) and [targetTokens] is
 * `target x usable` (what a later compaction reduces the history to).
 *
 * ## Estimate and correction
 * The whole prompt is estimated, not only its history: message texts, tool-call arguments, tool responses
 * and tool definitions (name, description, input schema), at [INITIAL_CHARS_PER_TOKEN] chars per token, as
 * Coday did (deliberately below the usual 4, for margin). As the estimate covers everything the provider
 * receives, it is compared to [usableTokens] directly, with no fixed overhead to subtract.
 *
 * Whenever a response reports its native prompt usage, [record] derives a measured chars-per-token ratio
 * from that very prompt, and later estimates of the invocation use it. The last observation wins instead
 * of an average: prompts of one invocation are successive extensions of each other, so the latest one
 * is the best predictor of the next, and the ratio follows a change in content (prose, then JSON-heavy
 * tool results) at once. A degenerate observation (images, truncated usage) is bounded by
 * [MIN_CHARS_PER_TOKEN]..[MAX_CHARS_PER_TOKEN] instead of being trusted blindly.
 *
 * Tool rounds may run on Reactor threads: the state is one immutable value swapped atomically.
 *
 * @param label identifies the invocation in logs (`case=<id> agent=<name> model=<m>`); never interpreted.
 */
class ContextBudget(
    val contextWindow: Long?,
    val maxCompletionTokens: Int? = null,
    val threshold: Double = DEFAULT_THRESHOLD,
    val target: Double = DEFAULT_TARGET,
    val label: String = "",
) {
    init {
        require(target > 0.0 && target < threshold && threshold <= 1.0) {
            "context budget fractions must satisfy 0 < target < threshold <= 1 (target=$target, threshold=$threshold)"
        }
    }

    /** Tokens a prompt may use: the window minus the completion allowance, or null when the budget is disabled. */
    val usableTokens: Long? =
        contextWindow?.let { it - (maxCompletionTokens ?: 0) }?.takeIf { it > 0 }

    val enabled: Boolean get() = usableTokens != null

    /** Estimated prompt size from which the budget counts as reached; null when disabled. */
    val triggerTokens: Long? = usableTokens?.let { floor(it * threshold).toLong() }

    /** Size a compaction reduces the history to; null when disabled. */
    val targetTokens: Long? = usableTokens?.let { floor(it * target).toLong() }

    private val state = AtomicReference(State())
    private val reported = AtomicBoolean(false)

    /** Characters of everything the provider receives for [prompt]. Never throws: a measure must not fail a run. */
    fun promptChars(prompt: Prompt): Long {
        val messages = prompt.instructions.sumOf { messageChars(it) }
        val options = prompt.options as? ToolCallingChatOptions
        val tools =
            options?.toolCallbacks.orEmpty().sumOf { callback ->
                val definition = callback.toolDefinition
                (definition.name().length + definition.description().length + definition.inputSchema().length).toLong()
            }
        return messages + tools
    }

    /** Tokens estimated for [chars] characters, with the current ratio. Remembered for [snapshot]. */
    fun estimateTokens(chars: Long): Long {
        val estimated = ceil(chars / state.get().charsPerToken).toLong()
        state.updateAndGet { it.copy(lastEstimatedTokens = estimated) }
        return estimated
    }

    /** Tokens estimated for the whole [prompt]. */
    fun estimate(prompt: Prompt): Long = estimateTokens(promptChars(prompt))

    /**
     * Records that a prompt of [promptChars] characters was measured by the provider at [observedPromptTokens]
     * tokens (input plus cache read and write). Ignored when either is not positive: such a response told
     * nothing, and a ratio from it would be noise.
     */
    fun record(
        promptChars: Long,
        observedPromptTokens: Long,
    ) {
        if (promptChars <= 0 || observedPromptTokens <= 0) return
        val ratio = (promptChars.toDouble() / observedPromptTokens).coerceIn(MIN_CHARS_PER_TOKEN, MAX_CHARS_PER_TOKEN)
        state.updateAndGet { it.copy(charsPerToken = ratio, lastObservedTokens = observedPromptTokens) }
    }

    /** Whether a prompt estimated at [estimatedTokens] has reached the trigger. Always false when disabled. */
    fun isTriggerReached(estimatedTokens: Long): Boolean = triggerTokens?.let { estimatedTokens >= it } ?: false

    /** True for the first caller only: lets the observer report the first time the trigger is reached, once. */
    fun markTriggerReported(): Boolean = reported.compareAndSet(false, true)

    fun snapshot(): Snapshot =
        state.get().let {
            Snapshot(
                usableTokens = usableTokens,
                lastObservedTokens = it.lastObservedTokens,
                lastEstimatedTokens = it.lastEstimatedTokens,
                charsPerToken = it.charsPerToken,
            )
        }

    /** What a log line needs: the numbers behind the latest decision. */
    data class Snapshot(
        val usableTokens: Long?,
        val lastObservedTokens: Long?,
        val lastEstimatedTokens: Long?,
        val charsPerToken: Double,
    )

    private data class State(
        val charsPerToken: Double = INITIAL_CHARS_PER_TOKEN,
        val lastObservedTokens: Long? = null,
        val lastEstimatedTokens: Long? = null,
    )

    private fun messageChars(message: Message): Long =
        message.text.orEmpty().length.toLong() +
            when (message) {
                is AssistantMessage -> message.toolCalls.sumOf { (it.name().length + it.arguments().length).toLong() }
                is ToolResponseMessage -> message.responses.sumOf { (it.name().length + it.responseData().length).toLong() }
                else -> 0L
            }

    companion object {
        /** Coday's margin: below the usual 4 chars per token, so the estimate errs on the large side. */
        const val INITIAL_CHARS_PER_TOKEN = 3.0
        const val MIN_CHARS_PER_TOKEN = 1.0
        const val MAX_CHARS_PER_TOKEN = 8.0
        const val DEFAULT_THRESHOLD = 0.8
        const val DEFAULT_TARGET = 0.5

        /** A budget that is never reached: for clients that observe nothing. */
        fun disabled(label: String = "") = ContextBudget(contextWindow = null, label = label)
    }
}
