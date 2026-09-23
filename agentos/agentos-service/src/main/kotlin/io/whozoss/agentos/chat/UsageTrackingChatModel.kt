package io.whozoss.agentos.chat

import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.usage.LlmUsage
import io.whozoss.agentos.usage.CostRunStopped
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
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
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks each provider request, including AgentSimple's tool rounds. Spring AI's internal
 * tool loop is disabled: its cumulative Usage loses native cache counts. The same tool
 * callbacks are executed here, after accounting and the user's cost confirmation.
 * Native chunks are inspected BEFORE MessageAggregator discards their native metadata.
 */
class UsageTrackingChatModel(
    private val delegate: ChatModel,
    private val accumulator: UsageAccumulator,
    private val apiType: AiApiType,
    private val model: AiModel,
    private val toolManager: ToolCallingManager = DefaultToolCallingManager.builder().build(),
    private val maxToolRounds: Int = 20,
) : ChatModel by delegate {
    override fun call(prompt: Prompt): ChatResponse = callRound(externalTools(prompt), 0)

    private fun callRound(
        prompt: Prompt,
        round: Int,
    ): ChatResponse {
        awaitPermission()
        val response =
            try {
                delegate.call(prompt)
            } catch (e: Exception) {
                accumulator.failed = true
                throw e
            }
        accumulator.record(CostCalculator.extract(response, apiType, model))
        if (!response.hasToolCalls()) return response
        if (round >= maxToolRounds) {
            accumulator.failed = true
            throw IllegalStateException("Maximum tool rounds reached")
        }
        awaitPermission()
        val result = toolManager.executeToolCalls(prompt, response)
        return if (result.returnDirect()) {
            ChatResponse(ToolExecutionResult.buildGenerations(result))
        } else {
            callRound(Prompt(result.conversationHistory(), prompt.options), round + 1)
        }
    }

    override fun stream(prompt: Prompt): Flux<ChatResponse> = streamRound(externalTools(prompt), 0)

    private fun streamRound(
        prompt: Prompt,
        round: Int,
    ): Flux<ChatResponse> =
        Flux.defer {
            val aggregated = AtomicReference<ChatResponse?>(null)
            val lastUsage = AtomicReference<LlmUsage?>(null)
            val raw =
                Mono
                    .fromFuture(accumulator.beforeCall())
                    .thenMany(Flux.defer { delegate.stream(prompt) })
                    .doOnNext { response ->
                        val usage = CostCalculator.extract(response, apiType, model)
                        if (usage != LlmUsage.ZERO) lastUsage.set(usage)
                    }.doOnComplete { lastUsage.getAndSet(null)?.let(accumulator::record) }
                    .doOnError { error ->
                        lastUsage.getAndSet(null)?.let(accumulator::record)
                        if (generateSequence(error) { it.cause }.none { it is CostRunStopped }) accumulator.failed = true
                    }.doOnCancel { lastUsage.getAndSet(null)?.let(accumulator::record) }
            MessageAggregator().aggregate(raw, aggregated::set).concatWith(
                Flux.defer {
                    val response = aggregated.get()
                    if (response == null || !response.hasToolCalls()) return@defer Flux.empty<ChatResponse>()
                    if (round >= maxToolRounds) {
                        accumulator.failed = true
                        return@defer Flux.error<ChatResponse>(IllegalStateException("Maximum tool rounds reached"))
                    }
                    Mono
                        .fromFuture(accumulator.beforeCall())
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
                },
            )
        }

    private fun externalTools(prompt: Prompt): Prompt {
        val copied: ChatOptions = (prompt.options ?: delegate.defaultOptions).copy()
        val options = copied as? ToolCallingChatOptions ?: ToolCallingChatOptions.builder().build()
        options.internalToolExecutionEnabled = false
        return Prompt(prompt.instructions, options)
    }

    private fun awaitPermission() {
        try {
            accumulator.beforeCall().join()
        } catch (e: CompletionException) {
            throw (e.cause ?: e)
        }
    }
}
