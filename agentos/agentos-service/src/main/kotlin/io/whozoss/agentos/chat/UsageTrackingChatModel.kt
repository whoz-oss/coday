package io.whozoss.agentos.chat

import io.whozoss.agentos.agent.AgentInterrupt
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.usage.LlmUsage
import io.whozoss.agentos.usage.CostRunStopped
import org.springframework.ai.chat.messages.AssistantMessage
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
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks each provider request, including AgentSimple's tool rounds. Spring AI's internal
 * tool loop is disabled: its cumulative Usage loses native cache counts. The same tool
 * callbacks are executed here, after accounting and the user's cost confirmation.
 * Native chunks are inspected BEFORE MessageAggregator discards their native metadata.
 *
 * After [maxToolRounds] tool rounds, a response that still asks for tools is not executed:
 * one more request asks the model to answer without tools and summarize its progress. That
 * summary never ends on a dropped tool call, and it carries [MAX_ITERATIONS_REACHED] in its
 * metadata so the agent can tell the user it stopped early. A response without tool calls at
 * that point completes the run normally.
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
        return delegate.call(prompt).also { accumulator.record(CostCalculator.extract(it, apiType, model)) }
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

    /** The provider stream behind the cost gate; its last native usage is recorded once, however it ends. */
    private fun trackedStream(prompt: Prompt): Flux<ChatResponse> {
        val lastUsage = AtomicReference<LlmUsage?>(null)
        return Mono
            .fromFuture(accumulator.beforeCall())
            .thenMany(Flux.defer { delegate.stream(prompt) })
            .doOnNext { response ->
                val usage = CostCalculator.extract(response, apiType, model)
                if (usage != LlmUsage.ZERO) lastUsage.set(usage)
            }.doOnComplete { lastUsage.getAndSet(null)?.let(accumulator::record) }
            .doOnError {
                lastUsage.getAndSet(null)?.let(accumulator::record)
            }.doOnCancel { lastUsage.getAndSet(null)?.let(accumulator::record) }
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
