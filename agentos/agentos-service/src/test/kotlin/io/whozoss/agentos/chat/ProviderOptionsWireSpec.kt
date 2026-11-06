package io.whozoss.agentos.chat

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.observation.ObservationRegistry
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.net.InetSocketAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Wire-level regression spec: the provider-specific options configured by [ChatModelFactory] must actually
 * reach the HTTP request sent by the chat client built by [ChatClientProvider] (issue #1538), for every
 * provider path.
 *
 * Those options (Anthropic `cacheOptions`, OpenAI `reasoning_effort` / `max_completion_tokens` /
 * `stream_options`, vLLM `chat_template_kwargs`...) live in the default options of the provider's chat model.
 * Spring AI merges runtime options (e.g. the generic tool-calling options created by
 * `prompt.toolCallbacks(...)`) over those defaults, and a wrapper model that hides the provider defaults, or
 * a merge that resets them, silently drops them: everything is configured, no error is raised, and the
 * option is never sent (for Anthropic: no `cache_control`, so no prompt caching).
 *
 * This spec therefore wires the REAL production chain ([ChatModelFactory] + [ChatClientProvider], with or
 * without usage tracking), points the provider at a local stub HTTP server, builds the prompt the way
 * `AgentSimple` does, and asserts on the raw request body captured by the stub.
 */
class ProviderOptionsWireSpec :
    StringSpec({
        val stub = ProviderStubServer().also { it.start() }
        val mapper = ObjectMapper()

        afterSpec { stub.stop() }
        beforeTest { stub.reset() }

        fun chatClient(
            apiType: AiApiType,
            usageTracking: Boolean,
            promptCachingEnabled: Boolean = true,
            modelName: String = "claude-sonnet-4-5",
            temperature: Double? = 0.7,
            maxCompletionTokens: Int? = 1024,
            reasoningEffort: ReasoningEffort? = null,
        ): ChatClient {
            val usageConfig = UsageConfigProperties(enabled = usageTracking)
            val factory =
                ChatModelFactory(
                    ObservationRegistry.NOOP,
                    AnthropicProperties(promptCachingEnabled = promptCachingEnabled),
                    usageConfig,
                )
            val providerId = UUID.randomUUID()
            val provider =
                AiProvider(
                    metadata = EntityMetadata(id = providerId),
                    namespaceId = UUID.randomUUID(),
                    name = "${apiType.name.lowercase()}-stub",
                    apiType = apiType,
                    baseUrl = stub.baseUrl,
                    apiKey = "sk-test",
                )
            val model =
                AiModel(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    aiProviderId = providerId,
                    apiModelName = modelName,
                    temperature = temperature,
                    maxCompletionTokens = maxCompletionTokens,
                    reasoningEffort = reasoningEffort,
                )
            return ChatClientProvider(factory, usageConfig = usageConfig)
                .getChatClient(model, provider, accumulator = if (usageTracking) UsageAccumulator() else null)
        }

        fun anthropicClient(
            promptCachingEnabled: Boolean,
            usageTracking: Boolean,
        ) = chatClient(AiApiType.Anthropic, usageTracking, promptCachingEnabled)

        // Long enough to be well above any minimum-length eligibility threshold for caching.
        val longText = "You are a meticulous assistant. ".repeat(300)

        fun prompt() = Prompt(listOf(SystemMessage(longText), UserMessage("$longText What is the weather in Paris?")))

        val weatherTool =
            object : ToolCallback {
                override fun getToolDefinition(): ToolDefinition =
                    ToolDefinition
                        .builder()
                        .name("get_weather")
                        .description("Return the weather of a city")
                        .inputSchema(
                            """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""",
                        ).build()

                override fun call(toolInput: String): String = "sunny"
            }

        val clockTool =
            object : ToolCallback {
                override fun getToolDefinition(): ToolDefinition =
                    ToolDefinition
                        .builder()
                        .name("get_time")
                        .description("Return the time in a timezone")
                        .inputSchema(
                            """{"type":"object","properties":{"timezone":{"type":"string"}},"required":["timezone"]}""",
                        ).build()

                override fun call(toolInput: String): String = "12:00"
            }

        /** The streamed text of the request, failing clearly if the stub's answer did not come through. */
        fun ChatClient.ChatClientRequestSpec.streamedText(): String =
            stream()
                .chatResponse()
                .collectList()
                .block(Duration.ofSeconds(30))!!
                .flatMap { it.results }
                .joinToString("") { it.output.text.orEmpty() }

        /** The single request the stub received on [path], parsed. Fails clearly if the call never reached it. */
        fun capturedRequest(path: String): JsonNode {
            val bodies = stub.requestBodies(path)
            withClue("expected exactly one POST $path to reach the stub") { bodies shouldHaveSize 1 }
            return mapper.readTree(bodies.single())
        }

        /** Short rendering for failure messages: the prompts are several KB of repeated text. */
        fun JsonNode?.preview(): String = this?.toString()?.take(160) ?: "<absent>"

        /** The request without its (huge) prompt content: what is left are the options actually sent. */
        fun JsonNode.options(): String = (deepCopy() as ObjectNode).apply { remove(listOf("messages", "system", "tools")) }.toString()

        fun JsonNode.hasCacheControlAnywhere(): Boolean = has("cache_control") || values().any { it.hasCacheControlAnywhere() }

        // ---------------------------------------------------------------------------------------------
        // Anthropic: prompt caching
        // ---------------------------------------------------------------------------------------------

        fun assertEphemeral(
            block: JsonNode?,
            where: String,
        ) {
            withClue("$where: cache_control is missing (block was: ${block.preview()})") {
                block?.get("cache_control") shouldNotBe null
            }
            block!!.get("cache_control").get("type").asText() shouldBe "ephemeral"
        }

        fun assertSystemCached(request: JsonNode) {
            val system = request.get("system")
            withClue("system must be sent as an array of blocks to carry cache_control, got: ${system.preview()}") {
                system?.isArray shouldBe true
            }
            assertEphemeral(system.values().last(), "last system block")
        }

        fun assertLastToolCached(request: JsonNode) {
            val tools = request.get("tools")
            withClue("tools must be sent as an array, got: ${tools.preview()}") { tools?.isArray shouldBe true }
            tools.size() shouldBe 2
            assertEphemeral(tools.values().last(), "last tool")
        }

        fun assertLastUserBlockCached(request: JsonNode) {
            val lastUser = request.get("messages").values().last { it.get("role").asText() == "user" }
            val content = lastUser.get("content")
            withClue("user content must be sent as an array of blocks to carry cache_control, got: ${content.preview()}") {
                content.isArray shouldBe true
            }
            assertEphemeral(content.values().last(), "last user content block")
        }

        "caching enabled, with tools, usage tracking enabled: cache_control reaches the wire" {
            anthropicClient(promptCachingEnabled = true, usageTracking = true)
                .prompt(prompt())
                .toolCallbacks(weatherTool, clockTool)
                .call()
                .content() shouldBe "Done"

            val request = capturedRequest(ProviderStubServer.ANTHROPIC_PATH)
            assertSystemCached(request)
            assertLastToolCached(request)
            assertLastUserBlockCached(request)
        }

        "caching enabled, with tools, usage tracking disabled: cache_control reaches the wire" {
            anthropicClient(promptCachingEnabled = true, usageTracking = false)
                .prompt(prompt())
                .toolCallbacks(weatherTool, clockTool)
                .call()
                .content() shouldBe "Done"

            val request = capturedRequest(ProviderStubServer.ANTHROPIC_PATH)
            assertSystemCached(request)
            assertLastToolCached(request)
            assertLastUserBlockCached(request)
        }

        "caching enabled, without tools, usage tracking disabled: cache_control reaches the wire" {
            anthropicClient(promptCachingEnabled = true, usageTracking = false)
                .prompt(prompt())
                .call()
                .content() shouldBe "Done"

            val request = capturedRequest(ProviderStubServer.ANTHROPIC_PATH)
            assertSystemCached(request)
            assertLastUserBlockCached(request)
        }

        "caching disabled, with tools: no cache_control anywhere on the wire" {
            anthropicClient(promptCachingEnabled = false, usageTracking = false)
                .prompt(prompt())
                .toolCallbacks(weatherTool, clockTool)
                .call()
                .content() shouldBe "Done"

            val request = capturedRequest(ProviderStubServer.ANTHROPIC_PATH)
            withClue("no cache_control expected when prompt caching is disabled, got: ${request.preview()}") {
                request.hasCacheControlAnywhere() shouldBe false
            }
        }

        // The streaming path used by AgentSimple: stream().chatResponse() through UsageTrackingChatModel.
        "caching enabled, with tools, usage tracking enabled, streaming: cache_control reaches the wire" {
            stub.streaming = true
            anthropicClient(promptCachingEnabled = true, usageTracking = true)
                .prompt(prompt())
                .toolCallbacks(weatherTool, clockTool)
                .streamedText() shouldBe "Done"

            val request = capturedRequest(ProviderStubServer.ANTHROPIC_PATH)
            request.get("stream").asBoolean() shouldBe true
            assertSystemCached(request)
            assertLastToolCached(request)
            assertLastUserBlockCached(request)
        }

        // ---------------------------------------------------------------------------------------------
        // OpenAI and vLLM (OpenAI-compatible): reasoning effort, token limit, usage streaming
        // ---------------------------------------------------------------------------------------------

        fun JsonNode.shouldHaveText(
            field: String,
            expected: String,
        ) {
            withClue("'$field' must be \"$expected\" on the wire, options sent were: ${options()}") {
                get(field)?.asText() shouldBe expected
            }
        }

        fun JsonNode.shouldHaveInt(
            field: String,
            expected: Int,
        ) {
            withClue("'$field' must be $expected on the wire, options sent were: ${options()}") {
                get(field)?.asInt() shouldBe expected
            }
        }

        fun JsonNode.shouldHaveTwoTools() {
            withClue("'tools' must hold the 2 declared tools, options sent were: ${options()}") {
                get("tools")?.size() shouldBe 2
            }
        }

        fun assertOpenAiOptions(request: JsonNode) {
            request.shouldHaveText("model", "gpt-test")
            request.shouldHaveText("reasoning_effort", "high")
            request.shouldHaveInt("max_completion_tokens", 1024)
            request.shouldHaveTwoTools()
        }

        fun assertStreamUsageRequested(request: JsonNode) {
            withClue("'stream' must be true on the wire, options sent were: ${request.options()}") {
                request.get("stream")?.asBoolean() shouldBe true
            }
            withClue("'stream_options.include_usage' must be true on the wire, options sent were: ${request.options()}") {
                request.get("stream_options")?.get("include_usage")?.asBoolean() shouldBe true
            }
        }

        fun openAiClient(usageTracking: Boolean) =
            chatClient(
                AiApiType.OpenAI,
                usageTracking,
                modelName = "gpt-test",
                temperature = 1.0,
                maxCompletionTokens = 1024,
                reasoningEffort = ReasoningEffort.HIGH,
            )

        "openai, with tools, usage tracking enabled: reasoning effort and token limit reach the wire" {
            openAiClient(usageTracking = true)
                .prompt(prompt())
                .toolCallbacks(weatherTool, clockTool)
                .call()
                .content() shouldBe "Done"

            assertOpenAiOptions(capturedRequest(ProviderStubServer.OPENAI_PATH))
        }

        "openai, with tools, usage tracking enabled, streaming: options and usage streaming reach the wire" {
            stub.streaming = true
            openAiClient(usageTracking = true)
                .prompt(prompt())
                .toolCallbacks(weatherTool, clockTool)
                .streamedText() shouldBe "Done"

            val request = capturedRequest(ProviderStubServer.OPENAI_PATH)
            assertOpenAiOptions(request)
            assertStreamUsageRequested(request)
        }

        "openai, with tools, usage tracking disabled: reasoning effort and token limit reach the wire" {
            openAiClient(usageTracking = false)
                .prompt(prompt())
                .toolCallbacks(weatherTool, clockTool)
                .call()
                .content() shouldBe "Done"

            assertOpenAiOptions(capturedRequest(ProviderStubServer.OPENAI_PATH))
        }

        "openai, with tools, usage tracking disabled, streaming: reasoning effort and token limit reach the wire" {
            stub.streaming = true
            openAiClient(usageTracking = false)
                .prompt(prompt())
                .toolCallbacks(weatherTool, clockTool)
                .streamedText() shouldBe "Done"

            val request = capturedRequest(ProviderStubServer.OPENAI_PATH)
            request.shouldHaveText("reasoning_effort", "high")
            request.shouldHaveInt("max_completion_tokens", 1024)
        }

        fun assertVllmOptions(request: JsonNode) {
            request.shouldHaveText("reasoning_effort", "medium")
            // Spring AI flattens `extraBody` at the root of the request body.
            withClue("'chat_template_kwargs.enable_thinking' must be true at the root, options sent were: ${request.options()}") {
                request.get("chat_template_kwargs")?.get("enable_thinking")?.asBoolean() shouldBe true
            }
            request.shouldHaveInt("max_tokens", 1024)
            request.shouldHaveTwoTools()
        }

        // Control: the same options without tools, to tell a tool-path drop from an everywhere drop.
        "vllm, without tools, usage tracking disabled, streaming: thinking options and token limit reach the wire" {
            stub.streaming = true
            chatClient(
                AiApiType.vLLM,
                usageTracking = false,
                modelName = "gpt-test",
                temperature = 1.0,
                maxCompletionTokens = 1024,
                reasoningEffort = ReasoningEffort.MEDIUM,
            ).prompt(prompt())
                .streamedText() shouldBe "Done"

            val request = capturedRequest(ProviderStubServer.OPENAI_PATH)
            request.shouldHaveText("reasoning_effort", "medium")
            withClue("'chat_template_kwargs.enable_thinking' must be true at the root, options sent were: ${request.options()}") {
                request.get("chat_template_kwargs")?.get("enable_thinking")?.asBoolean() shouldBe true
            }
            request.shouldHaveInt("max_tokens", 1024)
        }

        listOf(true, false).forEach { usageTracking ->
            val tracking = if (usageTracking) "enabled" else "disabled"
            // Disabled: vLLM drops extraBody when tools are passed, tracked in #1545. Re-enable with the fix.
            "vllm, with tools, usage tracking $tracking, streaming: thinking options and token limit reach the wire".config(
                enabled = false,
            ) {
                stub.streaming = true
                chatClient(
                    AiApiType.vLLM,
                    usageTracking = usageTracking,
                    modelName = "gpt-test",
                    temperature = 1.0,
                    maxCompletionTokens = 1024,
                    reasoningEffort = ReasoningEffort.MEDIUM,
                ).prompt(prompt())
                    .toolCallbacks(weatherTool, clockTool)
                    .streamedText() shouldBe "Done"

                assertVllmOptions(capturedRequest(ProviderStubServer.OPENAI_PATH))
            }
        }
    })

/**
 * Minimal stub of the Anthropic (`/v1/messages`) and OpenAI-compatible (`/v1/chat/completions`) APIs: records
 * the raw body of each POST per path and answers a valid completion, as plain JSON or, when [streaming] is
 * set, as a server-sent event stream.
 */
private class ProviderStubServer {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val bodies = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()

    @Volatile
    var streaming = false

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext(ANTHROPIC_PATH) { exchange -> handle(exchange, ANTHROPIC_JSON, ANTHROPIC_SSE) }
        server.createContext(OPENAI_PATH) { exchange -> handle(exchange, OPENAI_JSON, OPENAI_SSE) }
    }

    fun start() = server.start()

    fun stop() = server.stop(0)

    fun reset() {
        bodies.clear()
        streaming = false
    }

    fun requestBodies(path: String): List<String> = bodies[path].orEmpty()

    private fun handle(
        exchange: HttpExchange,
        json: String,
        sse: String,
    ) {
        exchange.use {
            bodies.computeIfAbsent(it.requestURI.path) { CopyOnWriteArrayList() } += String(it.requestBody.readBytes(), Charsets.UTF_8)
            val (contentType, body) = if (streaming) "text/event-stream" to sse else "application/json" to json
            val bytes = body.toByteArray(Charsets.UTF_8)
            it.responseHeaders.add("Content-Type", contentType)
            it.sendResponseHeaders(200, bytes.size.toLong())
            it.responseBody.use { out -> out.write(bytes) }
        }
    }

    companion object {
        const val ANTHROPIC_PATH = "/v1/messages"
        const val OPENAI_PATH = "/v1/chat/completions"

        private const val ANTHROPIC_JSON =
            """{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":[{"type":"text","text":"Done"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":2,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}"""

        private val ANTHROPIC_SSE =
            listOf(
                "message_start" to
                    """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}""",
                "content_block_start" to
                    """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                "content_block_delta" to
                    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Done"}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                "message_delta" to
                    """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":2}}""",
                "message_stop" to """{"type":"message_stop"}""",
            ).joinToString("") { (event, data) -> "event: $event\ndata: $data\n\n" }

        private const val OPENAI_JSON =
            """{"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-test","choices":[{"index":0,"message":{"role":"assistant","content":"Done"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}"""

        private val OPENAI_SSE =
            listOf(
                """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-test","choices":[{"index":0,"delta":{"role":"assistant","content":"Done"},"finish_reason":null}]}""",
                """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-test","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-test","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}""",
                "[DONE]",
            ).joinToString("") { "data: $it\n\n" }
    }
}
