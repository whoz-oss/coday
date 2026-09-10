package io.whozoss.agentos.chat

import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.usage.LlmUsage
import mu.KLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec
import org.springframework.ai.chat.client.ChatClient.StreamResponseSpec
import org.springframework.ai.chat.client.advisor.api.Advisor
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.template.TemplateRenderer
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.ToolCallbackProvider
import reactor.core.publisher.Flux
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import org.springframework.ai.chat.client.ChatClient.AdvisorSpec
import org.springframework.ai.chat.client.ChatClient.PromptSystemSpec
import org.springframework.ai.chat.client.ChatClient.PromptUserSpec
import org.springframework.core.io.Resource

/**
 * A [ChatClient] decorator that intercepts every completed LLM call and records the
 * token usage into a [UsageAccumulator].
 *
 * Implements [ChatClient] via Kotlin `by` delegation: only the three [prompt] overrides
 * are intercepted; all other [ChatClient] methods delegate transparently to [delegate].
 *
 * ## Call path
 *
 * - [call().chatResponse()] — usage is extracted from the single [ChatResponse].
 * - [stream().chatResponse()] — usage is extracted from the **last** chunk of the
 *   [Flux] (Anthropic always carries usage on the last chunk; OpenAI requires
 *   `streamOptions.includeUsage(true)`, which is configured in [ChatModelFactory]).
 *
 * Both paths delegate to [CostCalculator.extract] which handles the provider-specific
 * cache-token semantics via reflection.
 *
 * ## Placement in the decorator chain
 *
 * [UsageTrackingChatClient] wraps the raw [ChatClient] produced by [ChatModelFactory]
 * **before** [CompressingChatClient] is applied. This ensures usage is tracked on
 * the actual model response, not on the decompressed text:
 *
 * ```
 * ChatModel
 *   -> UsageTrackingChatClient     (usage extraction)
 *     -> CompressingChatClient     (ID compression, AgentAdvanced only)
 * ```
 */
class UsageTrackingChatClient(
    private val delegate: ChatClient,
    private val accumulator: UsageAccumulator,
    private val apiType: AiApiType,
    private val modelConfig: AiModel,
) : ChatClient by delegate {

    companion object : KLogging()

    override fun prompt(): ChatClientRequestSpec =
        TrackingRequestSpec(delegate.prompt())

    override fun prompt(prompt: Prompt): ChatClientRequestSpec =
        TrackingRequestSpec(delegate.prompt(prompt))

    override fun prompt(content: String): ChatClientRequestSpec =
        TrackingRequestSpec(delegate.prompt(content))

    // -------------------------------------------------------------------------
    // Inner wrapper classes
    // -------------------------------------------------------------------------

    /**
     * Wraps a [ChatClientRequestSpec] to ensure [call] and [stream] are always
     * intercepted by the tracking layer, regardless of which fluent methods were
     * chained before them.
     *
     * ## Why every fluent method must be overridden here
     *
     * Spring AI's [ChatClientRequestSpec] fluent methods (e.g. [toolCallbacks],
     * [messages], [system], …) are specified to return `this` — concretely, they
     * return the *implementation* instance, not the interface type. Because Kotlin
     * `by` delegation only intercepts methods on the wrapper class itself, any
     * fluent method that is NOT overridden here will return the *bare delegate*,
     * silently escaping the wrapper. The next call in the chain (including [call]
     * or [stream]) then runs on the unwrapped delegate and is never intercepted.
     *
     * Consequence: `prompt(...).toolCallbacks(...).stream()` would record no usage,
     * because [toolCallbacks] returns the delegate and [stream] is called on it directly.
     *
     * **Maintenance rule:** every method of [ChatClientRequestSpec] whose return type
     * is [ChatClientRequestSpec] MUST be listed here and re-wrap its result in a fresh
     * [TrackingRequestSpec]. Only [mutate] (which returns [ChatClient.Builder]) and
     * the terminal methods [call]/[stream] are exempt from this rule.
     *
     * If a future Spring AI upgrade adds a new fluent method to [ChatClientRequestSpec],
     * it must be added here to preserve the tracking invariant.
     */
    private inner class TrackingRequestSpec(
        private val delegate: ChatClientRequestSpec,
    ) : ChatClientRequestSpec by delegate {

        // --- fluent methods that must re-wrap to stay inside the tracking envelope ---

        override fun advisors(consumer: Consumer<AdvisorSpec>): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.advisors(consumer))

        override fun advisors(vararg advisors: Advisor): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.advisors(*advisors))

        override fun advisors(advisors: List<Advisor>): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.advisors(advisors))

        override fun messages(vararg messages: Message): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.messages(*messages))

        override fun messages(messages: List<Message>): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.messages(messages))

        override fun <T : ChatOptions> options(options: T): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.options(options))

        override fun toolNames(vararg toolNames: String): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.toolNames(*toolNames))

        override fun tools(vararg toolObjects: Any): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.tools(*toolObjects))

        override fun toolCallbacks(vararg toolCallbacks: ToolCallback): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.toolCallbacks(*toolCallbacks))

        override fun toolCallbacks(toolCallbacks: List<ToolCallback>): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.toolCallbacks(toolCallbacks))

        override fun toolCallbacks(vararg toolCallbackProviders: ToolCallbackProvider): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.toolCallbacks(*toolCallbackProviders))

        override fun toolContext(toolContext: Map<String, Any>): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.toolContext(toolContext))

        override fun system(text: String): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.system(text))

        override fun system(textResource: Resource, charset: Charset): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.system(textResource, charset))

        override fun system(text: Resource): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.system(text))

        override fun system(consumer: Consumer<PromptSystemSpec>): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.system(consumer))

        override fun user(text: String): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.user(text))

        override fun user(text: Resource, charset: Charset): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.user(text, charset))

        override fun user(text: Resource): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.user(text))

        override fun user(consumer: Consumer<PromptUserSpec>): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.user(consumer))

        override fun templateRenderer(templateRenderer: TemplateRenderer): ChatClientRequestSpec =
            TrackingRequestSpec(delegate.templateRenderer(templateRenderer))

        // --- terminal methods: intercept and wrap ---

        override fun call(): CallResponseSpec = TrackingCallSpec(delegate.call())

        override fun stream(): StreamResponseSpec = TrackingStreamSpec(delegate.stream())
    }

    private inner class TrackingCallSpec(
        private val delegate: CallResponseSpec,
    ) : CallResponseSpec by delegate {

        override fun chatResponse(): ChatResponse? {
            val response = delegate.chatResponse()
            if (response != null) {
                recordUsage(response)
            }
            return response
        }
    }

    private inner class TrackingStreamSpec(
        private val delegate: StreamResponseSpec,
    ) : StreamResponseSpec by delegate {

        /**
         * Intercepts the [Flux] to capture the last [ChatResponse] after the stream
         * completes and record its usage.
         *
         * The last chunk is the one that carries the aggregated usage for the whole
         * stream (both Anthropic and OpenAI with `includeUsage=true`).
         */
        override fun chatResponse(): Flux<ChatResponse> {
            val lastResponseRef = AtomicReference<ChatResponse?>(null)
            return delegate
                .chatResponse()
                .doOnNext { lastResponseRef.set(it) }
                .doOnComplete {
                    lastResponseRef.get()?.let { recordUsage(it) }
                }
        }

        override fun content(): Flux<String> =
            chatResponse().mapNotNull { response ->
                // Streaming metadata chunks (e.g. Anthropic MESSAGE_START with an empty
                // content list) have a null result — skip them rather than NPE.
                response.result?.output?.text?.takeIf { it.isNotEmpty() }
            }
    }

    // -------------------------------------------------------------------------
    // Shared helper
    // -------------------------------------------------------------------------

    private fun recordUsage(response: ChatResponse) {
        val usage = CostCalculator.extract(response, apiType, modelConfig)
        if (usage != LlmUsage.ZERO) {
            accumulator.record(usage)
            logger.debug {
                "[UsageTracking] recorded usage: in=${usage.inputTokens} out=${usage.outputTokens} " +
                    "cacheRead=${usage.cacheReadTokens} cacheWrite=${usage.cacheWriteTokens} " +
                    "total=${usage.totalTokens} cost=${usage.estimatedCostUsd}"
            }
        }
    }
}
