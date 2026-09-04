package io.whozoss.agentos.chat

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.Usage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.tool.ToolCallback
import reactor.core.publisher.Flux
import java.util.UUID

/**
 * Unit tests for [UsageTrackingChatClient].
 *
 * ## Scope
 *
 * These tests verify that the tracking decorator correctly intercepts usage metadata
 * from every call path, including ones that go through fluent methods of
 * [ChatClientRequestSpec] before terminating with [call] or [stream].
 *
 * The regression path — `prompt(...).toolCallbacks(...).stream()` — is the most
 * important: it is the path taken by [AgentSimple] whenever tools are registered,
 * which is the normal production case.
 *
 * ## Mock strategy
 *
 * We mock [ChatClient] with `relaxed = false` where possible so that any un-stubbed
 * call fails loudly rather than returning a default. The [ChatClientRequestSpec] mock
 * is the critical one: [toolCallbacks] must return the *same* mock (simulating the
 * real fluent chain) so the test can verify that [stream] is still intercepted after
 * the fluent call.
 */
class UsageTrackingChatClientUnitSpec : StringSpec({

    timeout = 5_000

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    fun modelWithPricing(): AiModel =
        AiModel(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            aiProviderId = UUID.randomUUID(),
            apiModelName = "test-model",
            pricingInputMTokens = 3.0,
            pricingOutputMTokens = 15.0,
        )

    fun modelWithoutPricing(): AiModel =
        AiModel(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            aiProviderId = UUID.randomUUID(),
            apiModelName = "test-model",
        )

    /**
     * Builds a [ChatResponse] with a real [Usage] object carrying the given counts.
     * The native usage is null (sufficient for tests that only check token totals;
     * the Anthropic/OpenAI reflection path is covered in [CostCalculatorUnitSpec]).
     */
    fun makeChatResponse(
        promptTokens: Int = 100,
        completionTokens: Int = 50,
        totalTokens: Int = promptTokens + completionTokens,
    ): ChatResponse {
        val usage = object : Usage {
            override fun getPromptTokens() = promptTokens
            override fun getCompletionTokens() = completionTokens
            override fun getTotalTokens() = totalTokens
            override fun getNativeUsage(): Any? = null
        }
        val metadata = ChatResponseMetadata.builder().usage(usage).build()
        return ChatResponse(
            listOf(Generation(AssistantMessage("ok"), ChatGenerationMetadata.NULL)),
            metadata,
        )
    }

    /**
     * A [ChatResponse] carrying a [Usage] object whose all token counts are null.
     * This simulates an intermediate streaming chunk that has not yet accumulated
     * any usage totals, or a response from a provider that doesn't report usage.
     *
     * Note: [ChatResponseMetadata.builder().build()] may produce a non-null [Usage]
     * with zero (non-null) counts depending on the Spring AI version, which would cause
     * [CostCalculator.extract] to compute a cost of 0.0 rather than returning
     * [LlmUsage.ZERO]. We explicitly set all counts to null to guarantee the ZERO path.
     */
    fun makeEmptyChatResponse(): ChatResponse {
        val nullUsage = object : Usage {
            override fun getPromptTokens(): Int? = null
            override fun getCompletionTokens(): Int? = null
            override fun getTotalTokens(): Int? = null
            override fun getNativeUsage(): Any? = null
        }
        val metadata = ChatResponseMetadata.builder().usage(nullUsage).build()
        return ChatResponse(
            listOf(Generation(AssistantMessage("ok"), ChatGenerationMetadata.NULL)),
            metadata,
        )
    }

    /** A [ChatResponse] whose [ChatResponse.getResult] is null (Anthropic MESSAGE_START chunk). */
    fun makeNullResultChatResponse(): ChatResponse =
        ChatResponse(emptyList<Generation>(), ChatResponseMetadata.builder().build())

    /**
     * Wires a [ChatClient] mock so that:
     * - [ChatClient.prompt(Prompt)] returns [reqSpec]
     * - [reqSpec.toolCallbacks(ToolCallback...)] returns [reqSpec] itself (fluent chain)
     * - [reqSpec.stream()] returns [streamSpec]
     * - [reqSpec.call()] returns [callSpec]
     *
     * [reqSpec] is deliberately relaxed so all other fluent methods also return [reqSpec]
     * (the mock's relaxed default for interface methods returning the same interface).
     */
    fun wireFluentChain(
        delegate: ChatClient,
        reqSpec: ChatClientRequestSpec,
        callSpec: ChatClient.CallResponseSpec,
        streamSpec: ChatClient.StreamResponseSpec,
    ) {
        every { delegate.prompt(any<Prompt>()) } returns reqSpec
        every { reqSpec.toolCallbacks(*anyVararg<ToolCallback>()) } returns reqSpec
        every { reqSpec.call() } returns callSpec
        every { reqSpec.stream() } returns streamSpec
    }

    // -------------------------------------------------------------------------
    // Point 1 (regression): fluent chain does not escape the tracking envelope
    // -------------------------------------------------------------------------

    /**
     * THE regression test.
     *
     * Before the fix, [ChatClientRequestSpec.toolCallbacks] was delegated transparently,
     * so it returned the bare delegate spec. The subsequent [stream] call was then made
     * on that bare spec, bypassing [TrackingStreamSpec], and usage was never recorded.
     *
     * This test fails without the fix and passes with it.
     */
    "prompt(...).toolCallbacks(...).stream() records usage in the accumulator" {
        val delegate = mockk<ChatClient>(relaxed = true)
        val reqSpec = mockk<ChatClientRequestSpec>(relaxed = true)
        val streamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
        wireFluentChain(delegate, reqSpec, callSpec, streamSpec)

        val response = makeChatResponse(promptTokens = 100, completionTokens = 50)
        every { streamSpec.chatResponse() } returns Flux.just(response)

        val accumulator = UsageAccumulator()
        val client = UsageTrackingChatClient(
            delegate = delegate,
            accumulator = accumulator,
            apiType = AiApiType.Anthropic,
            modelConfig = modelWithPricing(),
        )

        // Simulate AgentSimple: prompt → toolCallbacks → stream
        val toolCallback = mockk<ToolCallback>(relaxed = true)
        client
            .prompt(Prompt(emptyList()))
            .toolCallbacks(toolCallback)  // fluent — must stay inside tracking envelope
            .stream()
            .chatResponse()
            .collectList()
            .block()

        // Usage must have been recorded
        accumulator.total.outputTokens shouldBe 50L
        accumulator.total.totalTokens shouldBe 150L
    }

    // -------------------------------------------------------------------------
    // Point 2: call() path
    // -------------------------------------------------------------------------

    "prompt(...).call().chatResponse() records usage" {
        val delegate = mockk<ChatClient>(relaxed = true)
        val reqSpec = mockk<ChatClientRequestSpec>(relaxed = true)
        val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
        val streamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        wireFluentChain(delegate, reqSpec, callSpec, streamSpec)

        val response = makeChatResponse(promptTokens = 200, completionTokens = 80)
        every { callSpec.chatResponse() } returns response

        val accumulator = UsageAccumulator()
        val client = UsageTrackingChatClient(
            delegate = delegate,
            accumulator = accumulator,
            apiType = AiApiType.Anthropic,
            modelConfig = modelWithPricing(),
        )

        client.prompt(Prompt(emptyList())).call().chatResponse()

        accumulator.total.outputTokens shouldBe 80L
        accumulator.total.totalTokens shouldBe 280L
    }

    // -------------------------------------------------------------------------
    // Point 3: stream() path — last chunk carries usage
    // -------------------------------------------------------------------------

    "prompt(...).stream().chatResponse() records usage from last chunk" {
        val delegate = mockk<ChatClient>(relaxed = true)
        val reqSpec = mockk<ChatClientRequestSpec>(relaxed = true)
        val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
        val streamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        wireFluentChain(delegate, reqSpec, callSpec, streamSpec)

        // First chunk has no usage; last chunk has usage — mirrors real Anthropic streaming.
        val emptyUsageResponse = makeEmptyChatResponse()
        val finalResponse = makeChatResponse(promptTokens = 300, completionTokens = 120)
        every { streamSpec.chatResponse() } returns Flux.just(emptyUsageResponse, finalResponse)

        val accumulator = UsageAccumulator()
        val client = UsageTrackingChatClient(
            delegate = delegate,
            accumulator = accumulator,
            apiType = AiApiType.Anthropic,
            modelConfig = modelWithPricing(),
        )

        client.prompt(Prompt(emptyList())).stream().chatResponse().collectList().block()

        // The last chunk's usage is recorded; the empty-usage first chunk is ignored
        // (LlmUsage.ZERO is not recorded — see recordUsage guard).
        accumulator.total.outputTokens shouldBe 120L
        accumulator.total.totalTokens shouldBe 420L
    }

    // -------------------------------------------------------------------------
    // Point 4 (regression): null result in stream does not NPE in content()
    // -------------------------------------------------------------------------

    "stream content(): a chunk whose result is null is skipped, not fatal" {
        val delegate = mockk<ChatClient>(relaxed = true)
        val reqSpec = mockk<ChatClientRequestSpec>(relaxed = true)
        val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
        val streamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        wireFluentChain(delegate, reqSpec, callSpec, streamSpec)

        // First chunk: null result (Anthropic MESSAGE_START with empty content list)
        // Second chunk: real text
        val nullResultChunk = makeNullResultChatResponse()
        val realChunk = ChatResponse(
            listOf(Generation(AssistantMessage("hello"), ChatGenerationMetadata.NULL)),
            ChatResponseMetadata.builder().build(),
        )
        every { streamSpec.chatResponse() } returns Flux.just(nullResultChunk, realChunk)

        val accumulator = UsageAccumulator()
        val client = UsageTrackingChatClient(
            delegate = delegate,
            accumulator = accumulator,
            apiType = AiApiType.Anthropic,
            modelConfig = modelWithoutPricing(),
        )

        // Must not throw, must emit the non-null text chunk
        val chunks = client
            .prompt(Prompt(emptyList()))
            .stream()
            .content()
            .collectList()
            .block()!!

        chunks shouldNotBe null
        chunks.any { it.contains("hello") } shouldBe true
    }

    // -------------------------------------------------------------------------
    // Point 5: no usage metadata — accumulator stays at ZERO
    // -------------------------------------------------------------------------

    "response with no usage metadata records nothing in the accumulator" {
        val delegate = mockk<ChatClient>(relaxed = true)
        val reqSpec = mockk<ChatClientRequestSpec>(relaxed = true)
        val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
        val streamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        wireFluentChain(delegate, reqSpec, callSpec, streamSpec)

        every { callSpec.chatResponse() } returns makeEmptyChatResponse()

        val accumulator = UsageAccumulator()
        val client = UsageTrackingChatClient(
            delegate = delegate,
            accumulator = accumulator,
            apiType = AiApiType.Anthropic,
            modelConfig = modelWithPricing(),
        )

        client.prompt(Prompt(emptyList())).call().chatResponse()

        // LlmUsage.ZERO is not recorded — accumulator stays pristine
        accumulator.total.inputTokens shouldBe 0L
        accumulator.total.outputTokens shouldBe 0L
        accumulator.total.totalTokens shouldBe 0L
        accumulator.total.estimatedCostUsd.shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // Bonus: multiple fluent calls all stay inside the tracking envelope
    // -------------------------------------------------------------------------

    "prompt(...).system(...).toolCallbacks(...).stream() records usage" {
        val delegate = mockk<ChatClient>(relaxed = true)
        val reqSpec = mockk<ChatClientRequestSpec>(relaxed = true)
        val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
        val streamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        wireFluentChain(delegate, reqSpec, callSpec, streamSpec)
        // system(String) must also return the same reqSpec mock so the chain continues
        every { reqSpec.system(any<String>()) } returns reqSpec

        val response = makeChatResponse(promptTokens = 50, completionTokens = 25)
        every { streamSpec.chatResponse() } returns Flux.just(response)

        val accumulator = UsageAccumulator()
        val client = UsageTrackingChatClient(
            delegate = delegate,
            accumulator = accumulator,
            apiType = AiApiType.Anthropic,
            modelConfig = modelWithPricing(),
        )

        val toolCallback = mockk<ToolCallback>(relaxed = true)
        client
            .prompt(Prompt(emptyList()))
            .system("you are helpful")   // fluent #1
            .toolCallbacks(toolCallback) // fluent #2
            .stream()
            .chatResponse()
            .collectList()
            .block()

        accumulator.total.outputTokens shouldBe 25L
        accumulator.total.totalTokens shouldBe 75L
    }
})
