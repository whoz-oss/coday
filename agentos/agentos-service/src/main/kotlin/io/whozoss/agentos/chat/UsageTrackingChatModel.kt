package io.whozoss.agentos.chat

import io.whozoss.agentos.agent.AgentInterrupt
import io.whozoss.agentos.compaction.ContextBudget
import io.whozoss.agentos.agent.ToolImagesProduced
import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.usage.LlmUsage
import io.whozoss.agentos.usage.CostRunStopped
import mu.KLogging
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.model.MessageAggregator
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.DefaultToolCallingManager
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.model.tool.ToolExecutionResult
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The chat model of an agent invocation: it owns the tool loop and observes the context of every request,
 * and, when a [UsageAccumulator] is given, accounts for each request and gates it on the user's cost
 * confirmation. The name is historical: the cost part is the optional one.
 *
 * ## Always on (agent execution)
 * - **Tool loop.** Spring AI's internal loop is disabled and the same tool callbacks are executed here.
 *   Its cumulative Usage loses native cache counts, and it could not honour [maxToolRounds].
 *   After [maxToolRounds] tool rounds, a response that still asks for tools is not executed: one more
 *   request asks the model to answer without tools and summarize its progress. That summary never ends
 *   on a dropped tool call, and it carries [MAX_ITERATIONS_REACHED] in its metadata so the agent can tell
 *   the user it stopped early. A response without tool calls at that point completes the run normally.
 * - **Context observation.** Before each request, the prompt is estimated against [contextBudget]; after
 *   each response, the native prompt size, when the provider reports one, corrects the estimate. Today it
 *   only measures (DEBUG per request, one WARN per invocation when the trigger is reached): it changes
 *   neither the request nor the run. A budget without context window observes nothing.
 *
 * ## Optional (usage enabled, [accumulator] non-null)
 * Each request waits for the cost gate ([UsageAccumulator.beforeCall]) and its native usage is recorded;
 * a failure of the run marks the accumulator. Native chunks are inspected BEFORE MessageAggregator
 * discards their native metadata. Without an accumulator none of this happens: no gate, no record.
 */
class UsageTrackingChatModel(
    private val delegate: ChatModel,
    private val accumulator: UsageAccumulator?,
    private val apiType: AiApiType,
    private val model: AiModel,
    private val toolManager: ToolCallingManager = DefaultToolCallingManager.builder().build(),
    private val maxToolRounds: Int = LimitsConfigProperties().agentMaxIterations,
    private val contextBudget: ContextBudget = ContextBudget.disabled(),
) : ChatModel by delegate {
    /**
     * Kotlin `by delegate` does not forward Java interface default methods: without this override,
     * ChatClient would see generic DefaultChatOptions instead of the provider's options, and
     * provider-specific settings such as Anthropic cacheOptions would be lost (issue #1538).
     */
    override fun getDefaultOptions(): ChatOptions = delegate.defaultOptions

    /**
     * Needed because of limitation of Kotlin `by delegate`
     * Here default implementation provided by ChatModel is enough
     * because it already delegates to `call(prompt)`
     */
    override fun call(message: String?): String? {
        return super.call(message)
    }

    /**
     * Needed because of limitation of Kotlin `by delegate`
     * Here default implementation provided by ChatModel is enough
     * because it already delegates to `call(prompt)`
     */
    override fun call(vararg messages: Message?): String? {
        return super.call(*messages)
    }

    override fun call(prompt: Prompt): ChatResponse =
        try {
            callRound(externalTools(prompt), 0)
        } catch (e: Exception) {
            markFailure(e)
            throw e
        }

    private fun callRound(
        prompt: Prompt,
        round: Int,
    ): ChatResponse {
        val response = trackedCall(prompt)
        return when {
            !response.hasToolCalls() -> {
                response
            }

            round >= maxToolRounds -> {
                callSummary(prompt)
            }

            else -> {
                awaitPermission()
                val result = toolManager.executeToolCalls(prompt, response)
                if (result.returnDirect()) {
                    ChatResponse(ToolExecutionResult.buildGenerations(result))
                } else {
                    callRound(Prompt(result.conversationHistory(), prompt.options), round + 1)
                }
            }
        }
    }

    private fun trackedCall(prompt: Prompt): ChatResponse {
        awaitPermission()
        val promptChars = observeRequest(prompt)
        return delegate.call(prompt).also { response ->
            val usage = CostCalculator.extract(response, apiType, model)
            accumulator?.record(usage)
            observeResponse(promptChars, observedPromptTokens(response, usage))
        }
    }

    /** Keeps the provider's metadata (usage, id, model) and its generation unless the text must be completed. */
    private fun callSummary(prompt: Prompt): ChatResponse {
        val summary = trackedCall(summaryPrompt(prompt))
        val text = summary.result?.output?.text.orEmpty()
        val completed = completeSummary(text, droppedCall = summary.hasToolCalls())
        val generations =
            if (completed == text) {
                summary.results
            } else {
                val generationMetadata = summary.result?.metadata ?: ChatGenerationMetadata.NULL
                listOf(Generation(AssistantMessage(completed), generationMetadata))
            }
        return ChatResponse
            .builder()
            .from(summary)
            .generations(generations)
            .metadata(MAX_ITERATIONS_REACHED, maxToolRounds)
            .build()
    }

    /**
     * Needed because of limitation of Kotlin `by delegate`
     * Here default implementation provided by StreamingChatModel is enough
     * because it already delegates to `stream(prompt)`
     */
    override fun stream(message: String?): Flux<String?>? {
        return super.stream(message)
    }

    /**
     * Needed because of limitation of Kotlin `by delegate`
     * Here default implementation provided by StreamingChatModel is enough
     * because it already delegates to `stream(prompt)`
     */
    override fun stream(vararg messages: Message?): Flux<String?>? {
        return super.stream(*messages)
    }

    override fun stream(prompt: Prompt): Flux<ChatResponse> = streamRound(externalTools(prompt), 0).doOnError(::markFailure)

    private fun streamRound(
        prompt: Prompt,
        round: Int,
    ): Flux<ChatResponse> =
        Flux.defer {
            val aggregated = AtomicReference<ChatResponse?>(null)
            MessageAggregator().aggregate(trackedStream(prompt), aggregated::set).concatWith(
                Flux.defer {
                    val response = aggregated.get()
                    when {
                        response == null || !response.hasToolCalls() -> {
                            Flux.empty()
                        }

                        round >= maxToolRounds -> {
                            streamSummary(prompt)
                        }

                        else -> {
                            costGate()
                                .then(
                                    Mono
                                        .fromCallable { toolManager.executeToolCalls(prompt, response) }
                                        .subscribeOn(Schedulers.boundedElastic()),
                                ).flatMapMany { result ->
                                    if (result.returnDirect()) {
                                        Flux.just(ChatResponse(ToolExecutionResult.buildGenerations(result)))
                                    } else {
                                        streamRound(Prompt(result.conversationHistory(), prompt.options), round + 1)
                                    }
                                }
                        }
                    }
                },
            )
        }

    /**
     * The provider stream behind the cost gate. Its last native usage is recorded once, however it ends, and
     * so is the prompt size it reported, which corrects the budget's estimate.
     */
    private fun trackedStream(prompt: Prompt): Flux<ChatResponse> {
        val lastUsage = AtomicReference<LlmUsage?>(null)
        val lastPromptTokens = AtomicLong(0L)
        val promptChars = AtomicLong(0L)
        val finish = {
            lastUsage.getAndSet(null)?.let { accumulator?.record(it) }
            observeResponse(promptChars.get(), lastPromptTokens.getAndSet(0L))
        }
        return costGate()
            .thenMany(
                Flux.defer {
                    promptChars.set(observeRequest(prompt))
                    delegate.stream(prompt)
                },
            ).doOnNext { response ->
                val usage = CostCalculator.extract(response, apiType, model)
                if (usage != LlmUsage.ZERO) lastUsage.set(usage)
                observedPromptTokens(response, usage).takeIf { it > 0 }?.let(lastPromptTokens::set)
            }.doOnComplete { finish() }
            .doOnError { finish() }
            .doOnCancel { finish() }
    }

    /** The summary is streamed as it comes; a last chunk adds what completes it, and the limit marker. */
    private fun streamSummary(prompt: Prompt): Flux<ChatResponse> =
        Flux.defer {
            val aggregated = AtomicReference<ChatResponse?>(null)
            MessageAggregator().aggregate(trackedStream(summaryPrompt(prompt)), aggregated::set).concatWith(
                Flux.defer {
                    val summary = aggregated.get()
                    val streamed = summary?.result?.output?.text.orEmpty()
                    val completed = completeSummary(streamed, droppedCall = summary?.hasToolCalls() == true)
                    Flux.just(limitReached(completed.removePrefix(streamed)))
                },
            )
        }

    /** Tools stay declared (Anthropic rejects a tool history without tool definitions): the prompt forbids them. */
    private fun summaryPrompt(prompt: Prompt): Prompt =
        Prompt(
            prompt.instructions +
                UserMessage(
                    "You have reached the maximum number of tool rounds for this turn ($maxToolRounds). " +
                        "Do not call any tool. Answer now, in the language of the conversation: summarize what you " +
                        "have done and found so far, then list what remains to be done. " +
                        "The user can ask you to continue.",
                ),
            prompt.options,
        )

    /** An empty summary, or one announcing a tool call that will not run, gets the fallback sentence. */
    private fun completeSummary(
        text: String,
        droppedCall: Boolean,
    ): String =
        when {
            text.isBlank() -> fallbackSummary()
            droppedCall -> "$text\n\n${fallbackSummary()}"
            else -> text
        }

    private fun fallbackSummary() =
        "I reached the maximum number of tool rounds ($maxToolRounds) before finishing. Ask me to continue."

    private fun limitReached(text: String) =
        ChatResponse(
            listOf(Generation(AssistantMessage(text))),
            ChatResponseMetadata.builder().keyValue(MAX_ITERATIONS_REACHED, maxToolRounds).build(),
        )

    private fun markFailure(error: Throwable) {
        val interrupted =
            generateSequence(error) { it.cause }.any {
                it is AgentInterrupt || it is ToolImagesProduced || it is CostRunStopped || it is CancellationException
            }
        if (!interrupted) accumulator?.failed = true
    }

    private fun externalTools(prompt: Prompt): Prompt {
        val copied: ChatOptions = (prompt.options ?: delegate.defaultOptions).copy()
        val options = copied as? ToolCallingChatOptions ?: ToolCallingChatOptions.builder().build()
        options.internalToolExecutionEnabled = false
        return Prompt(prompt.instructions, options)
    }

    private fun awaitPermission() {
        val gate = accumulator ?: return
        try {
            gate.beforeCall().join()
        } catch (e: CompletionException) {
            throw (e.cause ?: e)
        }
    }

    /** The cost gate as a publisher; completes at once without usage tracking. */
    private fun costGate(): Mono<Void> = accumulator?.let { Mono.fromFuture(it.beforeCall()) } ?: Mono.empty()

    /**
     * Estimates the prompt about to be sent and reports it when it reaches the trigger. Returns the prompt's
     * size in characters, kept to correct the estimate once the provider has measured it. A measure must not
     * fail a run: anything it throws is logged and ignored.
     */
    private fun observeRequest(prompt: Prompt): Long {
        if (!contextBudget.enabled) return 0L
        return try {
            val chars = contextBudget.promptChars(prompt)
            val estimated = contextBudget.estimateTokens(chars)
            val reached = contextBudget.isTriggerReached(estimated)
            val details = describe(contextBudget.snapshot())
            logger.debug {
                "[ContextBudget] ${contextBudget.label} prompt ≈ $estimated tokens, trigger ${contextBudget.triggerTokens} " +
                    "of usable ${contextBudget.usableTokens} ($details)"
            }
            if (reached && contextBudget.markTriggerReported()) {
                logger.warn {
                    "[ContextBudget] ${contextBudget.label} prompt ≈ $estimated tokens >= trigger ${contextBudget.triggerTokens} " +
                        "of usable ${contextBudget.usableTokens} ($details)"
                }
            }
            chars
        } catch (e: Exception) {
            logger.debug(e) { "[ContextBudget] ${contextBudget.label} could not measure the prompt" }
            0L
        }
    }

    private fun observeResponse(
        promptChars: Long,
        observedTokens: Long,
    ) {
        if (contextBudget.enabled) contextBudget.record(promptChars, observedTokens)
    }

    private fun describe(snapshot: ContextBudget.Snapshot) =
        "observed=${snapshot.lastObservedTokens ?: "n/a"}, ratio=${"%.2f".format(Locale.ROOT, snapshot.charsPerToken)}"

    /**
     * Size of the prompt the provider says it received: input plus cache read and write when the native usage
     * is readable (Anthropic, OpenAI-compatible), else the generic prompt count (Gemini, others), else 0.
     */
    private fun observedPromptTokens(
        response: ChatResponse,
        usage: LlmUsage,
    ): Long {
        val native = usage.inputTokens + usage.cacheReadTokens + usage.cacheWriteTokens
        return if (native > 0) native else (response.metadata.usage?.promptTokens ?: 0).toLong()
    }

    companion object : KLogging() {
        /** Response metadata key: the tool round limit the run reached, set on its last response. */
        const val MAX_ITERATIONS_REACHED = "agentos.maxIterationsReached"
    }
}
