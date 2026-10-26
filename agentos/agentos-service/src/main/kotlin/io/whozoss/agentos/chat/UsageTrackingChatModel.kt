package io.whozoss.agentos.chat

import io.whozoss.agentos.agent.AgentInterrupt
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.usage.LlmUsage
import io.whozoss.agentos.usage.CostRunStopped
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.UserMessage
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
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks each provider request, including AgentSimple's tool rounds. Spring AI's internal
 * tool loop is disabled: its cumulative Usage loses native cache counts. The same tool
 * callbacks are executed here, after accounting and the user's cost confirmation.
 * Native chunks are inspected BEFORE MessageAggregator discards their native metadata.
 *
 * After [maxToolRounds] tool rounds, the next request asks the model to answer without tools
 * and summarize its progress. That last answer executes no tool, is never empty and carries
 * [MAX_ITERATIONS_REACHED] in its metadata, so the agent can tell the user it stopped early.
 */
class UsageTrackingChatModel(
    private val delegate: ChatModel,
    private val accumulator: UsageAccumulator,
    private val apiType: AiApiType,
    private val model: AiModel,
    private val toolManager: ToolCallingManager = DefaultToolCallingManager.builder().build(),
    private val maxToolRounds: Int = 20,
) : ChatModel by delegate {
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
        awaitPermission()
        val lastRound = round >= maxToolRounds
        val response = delegate.call(if (lastRound) summaryPrompt(prompt) else prompt)
        accumulator.record(CostCalculator.extract(response, apiType, model))
        return when {
            lastRound -> {
                limitReached(response.result?.output?.text.orEmpty().ifBlank { fallbackSummary() })
            }

            !response.hasToolCalls() -> {
                response
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

    override fun stream(prompt: Prompt): Flux<ChatResponse> = streamRound(externalTools(prompt), 0).doOnError(::markFailure)

    private fun streamRound(
        prompt: Prompt,
        round: Int,
    ): Flux<ChatResponse> =
        Flux.defer {
            val lastRound = round >= maxToolRounds
            val aggregated = AtomicReference<ChatResponse?>(null)
            val lastUsage = AtomicReference<LlmUsage?>(null)
            val raw =
                Mono
                    .fromFuture(accumulator.beforeCall())
                    .thenMany(Flux.defer { delegate.stream(if (lastRound) summaryPrompt(prompt) else prompt) })
                    .doOnNext { response ->
                        val usage = CostCalculator.extract(response, apiType, model)
                        if (usage != LlmUsage.ZERO) lastUsage.set(usage)
                    }.doOnComplete { lastUsage.getAndSet(null)?.let(accumulator::record) }
                    .doOnError {
                        lastUsage.getAndSet(null)?.let(accumulator::record)
                    }.doOnCancel { lastUsage.getAndSet(null)?.let(accumulator::record) }
            MessageAggregator().aggregate(raw, aggregated::set).concatWith(
                Flux.defer {
                    val response = aggregated.get()
                    when {
                        lastRound -> {
                            // The answer was already streamed: only a missing one is replaced.
                            val streamed = response?.result?.output?.text.orEmpty()
                            Flux.just(limitReached(if (streamed.isBlank()) fallbackSummary() else ""))
                        }

                        response == null || !response.hasToolCalls() -> {
                            Flux.empty()
                        }

                        else -> {
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
                        }
                    }
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
                it is AgentInterrupt || it is CostRunStopped || it is CancellationException
            }
        if (!interrupted) accumulator.failed = true
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

    companion object {
        /** Response metadata key: the tool round limit the run reached, set on its last response. */
        const val MAX_ITERATIONS_REACHED = "agentos.maxIterationsReached"
    }
}
