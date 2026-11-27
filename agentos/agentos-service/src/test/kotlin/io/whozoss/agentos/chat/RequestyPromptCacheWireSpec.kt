package io.whozoss.agentos.chat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.micrometer.observation.ObservationRegistry
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.api.user.PromptCacheTtl
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.DefaultToolCallingManager
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.retry.RetryUtils
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.JdkClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.util.UUID

/**
 * Wire-level spec of [RequestyPromptCache] hooked into the real [ChatModelFactory] + [ChatClientProvider] chain:
 * what actually leaves AgentOS for an OpenAI-compatible provider, for blocking calls (RestClient) and for
 * streaming calls (WebClient, the path agent turns use).
 *
 * The loopback [ProviderStubServer] stands in for Requesty through the internal [ChatModelFactory] constructor;
 * the "not Requesty" cases keep the production host matching, which never matches 127.0.0.1.
 *
 * "Unchanged" is checked byte for byte against the construction path that existed before the setting: a plain
 * `OpenAiApi.Builder().baseUrl().apiKey().build()` model, with no RestClient / WebClient builder of ours.
 */
class RequestyPromptCacheWireSpec :
    StringSpec({
        val stub = ProviderStubServer().also { it.start() }
        val mapper = ObjectMapper()

        afterSpec { stub.stop() }
        beforeTest { stub.reset() }

        val claude = "anthropic/claude-sonnet-4-5"

        fun chatClient(
            ttl: PromptCacheTtl,
            requesty: Boolean,
            usageTracking: Boolean = true,
            modelName: String = claude,
            modelPattern: String = RequestyPromptCacheProperties.DEFAULT_MODEL_PATTERN,
        ): ChatClient {
            val usageConfig = UsageConfigProperties(enabled = usageTracking)
            val factory =
                ChatModelFactory(
                    ObservationRegistry.NOOP,
                    AnthropicProperties(promptCachingEnabled = true),
                    usageConfig,
                    RequestyPromptCacheProperties(modelPattern),
                    isRequestyBaseUrl = { baseUrl -> requesty || RequestyPromptCache.isRequesty(baseUrl) },
                )
            val providerId = UUID.randomUUID()
            val provider =
                AiProvider(
                    metadata = EntityMetadata(id = providerId),
                    namespaceId = UUID.randomUUID(),
                    name = "requesty-stub",
                    apiType = AiApiType.OpenAI,
                    baseUrl = stub.baseUrl,
                    apiKey = "sk-test",
                )
            val model =
                AiModel(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    aiProviderId = providerId,
                    apiModelName = modelName,
                    temperature = 0.7,
                    maxCompletionTokens = 1024,
                )
            return ChatClientProvider(factory, usageConfig = usageConfig).getChatClient(
                model,
                provider,
                caseId = "case-1",
                accumulator = if (usageTracking) UsageAccumulator() else null,
                promptCacheTtl = ttl,
            )
        }

        /**
         * The chat client as built before the setting existed (no usage tracking): the reference for "unchanged".
         */
        fun originalChatClient(modelName: String = claude): ChatClient {
            val api =
                OpenAiApi
                    .Builder()
                    .baseUrl(stub.baseUrl)
                    .apiKey("sk-test")
                    .build()
            val options =
                OpenAiChatOptions
                    .builder()
                    .temperature(0.7)
                    .model(modelName)
                    .maxCompletionTokens(1024)
                    .build()
            val model =
                OpenAiChatModel(
                    api,
                    options,
                    DefaultToolCallingManager.builder().build(),
                    RetryUtils.DEFAULT_RETRY_TEMPLATE,
                    ObservationRegistry.NOOP,
                    DefaultToolExecutionEligibilityPredicate(),
                )
            return ChatClient.builder(model).build()
        }

        val weatherTool =
            object : ToolCallback {
                override fun getToolDefinition(): ToolDefinition =
                    ToolDefinition
                        .builder()
                        .name("get_weather")
                        .description("Return the weather of a city")
                        .inputSchema("""{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""")
                        .build()

                override fun call(toolInput: String): String = "sunny"
            }

        // A conversation in the middle of a tool round: the last message is a tool result, and the assistant
        // message before it only carries a tool call.
        fun prompt() =
            Prompt(
                listOf(
                    SystemMessage("You are a meticulous assistant."),
                    UserMessage("What is the weather in Paris?"),
                    AssistantMessage
                        .builder()
                        .content("")
                        .toolCalls(listOf(AssistantMessage.ToolCall("call-1", "function", "get_weather", """{"city":"Paris"}""")))
                        .build(),
                    ToolResponseMessage
                        .builder()
                        .responses(listOf(ToolResponseMessage.ToolResponse("call-1", "get_weather", "sunny")))
                        .build(),
                ),
            )

        /** What the stub received: the raw body and the headers (names lowercased). */
        data class Sent(
            val body: String,
            val headers: Map<String, String>,
        ) {
            /** The headers that frame and authenticate the request; the host differs per connection only by port. */
            val framing: Map<String, String> get() = headers - "host"
        }

        /** The single chat completions request the stub received, its framing and authentication checked. */
        fun received(): Sent {
            val bodies = stub.requestBodies(ProviderStubServer.OPENAI_PATH)
            withClue("expected exactly one POST ${ProviderStubServer.OPENAI_PATH} to reach the stub") { bodies shouldHaveSize 1 }
            val sent = Sent(bodies.single(), stub.requestHeaders(ProviderStubServer.OPENAI_PATH).single())
            withClue("headers sent were: ${sent.headers}") {
                sent.headers["authorization"] shouldBe "Bearer sk-test"
                // A Content-Length, when sent, must match the body actually sent; otherwise the body is chunked.
                sent.headers["content-length"]?.let { it shouldBe sent.body.toByteArray(Charsets.UTF_8).size.toString() }
                    ?: (sent.headers["transfer-encoding"] shouldBe "chunked")
            }
            return sent
        }

        /** Sends the prompt blocking or streaming and returns what the stub received. */
        fun send(
            client: ChatClient,
            streaming: Boolean,
            prompt: Prompt = prompt(),
        ): Sent {
            stub.reset()
            stub.streaming = streaming
            val request = client.prompt(prompt).toolCallbacks(weatherTool)
            val answer =
                if (streaming) {
                    request
                        .stream()
                        .chatResponse()
                        .collectList()
                        .block(Duration.ofSeconds(30))!!
                        .flatMap { it.results }
                        .joinToString("") { it.output.text.orEmpty() }
                } else {
                    request.call().content()
                }
            answer shouldBe "Done"
            return received()
        }

        fun JsonNode.message(role: String): JsonNode = get("messages").single { it.get("role").asText() == role }

        fun JsonNode.cacheControlCount(): Int =
            (if (has("cache_control")) 1 else 0) + elements().asSequence().sumOf { it.cacheControlCount() }

        fun assertOneHourMarkers(
            body: String,
            model: String = claude,
        ) {
            val request = mapper.readTree(body)
            withClue("requesty.auto_cache must be false, body was: $body") {
                request.get("requesty")?.get("auto_cache")?.asBoolean() shouldBe false
            }
            val oneHour = mapper.readTree("""{"type":"ephemeral","ttl":"1h"}""")
            withClue("the system message must carry a 1h cache_control, body was: $body") {
                (request.message("system").get("content").last().get("cache_control") == oneHour) shouldBe true
            }
            withClue("the last message (tool result) must carry a 1h cache_control, body was: $body") {
                val tool = request.message("tool")
                tool.get("tool_call_id").asText() shouldBe "call-1"
                (tool.get("content").last().get("cache_control") == oneHour) shouldBe true
            }
            withClue("the assistant tool-call message must not be touched, body was: $body") {
                request.message("assistant").cacheControlCount() shouldBe 0
            }
            request.cacheControlCount() shouldBe 2
            // The options Spring AI set are still there.
            request.get("model").asText() shouldBe model
            request.get("max_completion_tokens").asInt() shouldBe 1024
            request.get("tools").size() shouldBe 1
        }

        listOf(false, true).forEach { streaming ->
            val mode = if (streaming) "streaming (WebClient)" else "blocking (RestClient)"

            "$mode, Requesty with 1h: the body carries 1h markers and turns automatic caching off" {
                val sent = send(chatClient(PromptCacheTtl.EXTENDED, requesty = true), streaming)

                assertOneHourMarkers(sent.body)
                (mapper.readTree(sent.body).get("stream")?.asBoolean() ?: false) shouldBe streaming
                // The rewritten body is sent with its own, exact length (checked in send).
                sent.headers["content-length"] shouldBe sent.body.toByteArray(Charsets.UTF_8).size.toString()
            }

            "$mode, Requesty with 1h and usage tracking disabled: the body carries 1h markers" {
                assertOneHourMarkers(send(chatClient(PromptCacheTtl.EXTENDED, requesty = true, usageTracking = false), streaming).body)
            }

            "$mode, Requesty with 5m: the body is byte-for-byte the one sent before the setting existed" {
                val baseline = send(originalChatClient(), streaming)
                val fiveMinutes = send(chatClient(PromptCacheTtl.STANDARD, requesty = true, usageTracking = false), streaming)

                fiveMinutes.body shouldBe baseline.body
                fiveMinutes.framing shouldBe baseline.framing
                withClue("no cache field expected with 5m, body was: ${fiveMinutes.body}") {
                    fiveMinutes.body.contains("cache_control") shouldBe false
                    fiveMinutes.body.contains("requesty") shouldBe false
                }
            }

            "$mode, another provider with 1h: the body is byte-for-byte the one sent before the setting existed" {
                val baseline = send(originalChatClient(), streaming)
                val otherProvider = send(chatClient(PromptCacheTtl.EXTENDED, requesty = false, usageTracking = false), streaming)

                otherProvider.body shouldBe baseline.body
                otherProvider.framing shouldBe baseline.framing
                otherProvider.body.contains("cache_control") shouldBe false
            }

            "$mode, a policy added by a custom model pattern on Requesty with 1h: the body carries 1h markers" {
                val policy = "policy/sonnet-with-fallback"
                val sent =
                    send(
                        chatClient(
                            PromptCacheTtl.EXTENDED,
                            requesty = true,
                            modelName = policy,
                            modelPattern = "(?i)claude|^policy/sonnet-with-fallback$",
                        ),
                        streaming,
                    )

                assertOneHourMarkers(sent.body, model = policy)
            }

            "$mode, a non-Claude model on Requesty with 1h: the body is byte-for-byte the one sent before the setting existed" {
                val gpt = "openai/gpt-5"
                val baseline = send(originalChatClient(gpt), streaming)
                val otherModel =
                    send(chatClient(PromptCacheTtl.EXTENDED, requesty = true, usageTracking = false, modelName = gpt), streaming)

                otherModel.body shouldBe baseline.body
                otherModel.framing shouldBe baseline.framing
                otherModel.body.contains("cache_control") shouldBe false
            }

            "$mode, Requesty with 1h and a body above the rewrite cap: the body is byte-for-byte the one sent before" {
                val huge = Prompt(listOf(SystemMessage("System"), UserMessage("a".repeat(RequestyPromptCache.MAX_REWRITE_BYTES.toInt()))))
                val baseline = send(originalChatClient(), streaming, huge)
                val oneHour = send(chatClient(PromptCacheTtl.EXTENDED, requesty = true, usageTracking = false), streaming, huge)

                (oneHour.body == baseline.body) shouldBe true
                // Streaming keeps the encoder's Content-Length; blocking calls with the hook are buffered, so they
                // carry a Content-Length where the original is chunked (its exactness is checked in send).
                if (streaming) oneHour.framing shouldBe baseline.framing
                oneHour.body.contains("cache_control") shouldBe false
            }
        }

        // ---------------------------------------------------------------------------------------------
        // The WebClient hook on the JDK connector (the one Spring AI's streaming calls use here), directly
        // ---------------------------------------------------------------------------------------------

        /** POSTs [body], JSON-encoded by WebClient's Jackson encoder as for Spring AI, through the hook. */
        fun postThroughJdkConnector(body: Map<String, Any>): Sent {
            stub.reset()
            WebClient
                .builder()
                .clientConnector(JdkClientHttpConnector())
                .baseUrl(stub.baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer sk-test")
                .filter(RequestyPromptCache.filter())
                .build()
                .post()
                .uri(ProviderStubServer.OPENAI_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String::class.java)
                .block(Duration.ofSeconds(30))
            return received()
        }

        fun chatBody(userText: String): Map<String, Any> =
            linkedMapOf("model" to claude, "messages" to listOf(linkedMapOf("role" to "user", "content" to userText)))

        "JDK connector, body above the rewrite cap: the original body arrives byte for byte with its Content-Length" {
            val body = chatBody("a".repeat(RequestyPromptCache.MAX_REWRITE_BYTES.toInt()))
            val expected = mapper.writeValueAsString(body)

            val sent = postThroughJdkConnector(body)

            (sent.body == expected) shouldBe true
            sent.headers["content-length"] shouldBe expected.toByteArray(Charsets.UTF_8).size.toString()
        }

        "JDK connector, body within the rewrite cap: the body arrives rewritten with its new Content-Length" {
            val body = chatBody("Hello")

            val sent = postThroughJdkConnector(body)

            val request = mapper.readTree(sent.body)
            request.get("requesty").get("auto_cache").asBoolean() shouldBe false
            (request.get("messages").get(0).get("content").get(0).get("cache_control") ==
                mapper.readTree("""{"type":"ephemeral","ttl":"1h"}""")) shouldBe true
            sent.headers["content-length"] shouldBe sent.body.toByteArray(Charsets.UTF_8).size.toString()
        }
    })
