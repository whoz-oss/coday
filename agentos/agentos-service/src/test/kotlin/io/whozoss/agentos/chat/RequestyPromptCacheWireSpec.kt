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
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import java.time.Duration
import java.util.UUID

/**
 * Wire-level spec of [RequestyPromptCache] hooked into the real [ChatModelFactory] + [ChatClientProvider] chain:
 * what actually leaves AgentOS for an OpenAI-compatible provider, for blocking calls (RestClient) and for
 * streaming calls (WebClient, the path agent turns use).
 *
 * The loopback [ProviderStubServer] stands in for Requesty through [ChatModelFactory.isRequestyBaseUrl]; the
 * "not Requesty" cases keep the production host matching, which never matches 127.0.0.1.
 */
class RequestyPromptCacheWireSpec :
    StringSpec({
        val stub = ProviderStubServer().also { it.start() }
        val mapper = ObjectMapper()

        afterSpec { stub.stop() }
        beforeTest { stub.reset() }

        fun chatClient(
            ttl: PromptCacheTtl,
            requesty: Boolean,
            usageTracking: Boolean = true,
        ): ChatClient {
            val usageConfig = UsageConfigProperties(enabled = usageTracking)
            val factory = ChatModelFactory(ObservationRegistry.NOOP, AnthropicProperties(promptCachingEnabled = true), usageConfig)
            if (requesty) factory.isRequestyBaseUrl = { true }
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
                    apiModelName = "anthropic/claude-sonnet-4-5",
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

        /** Sends the prompt blocking or streaming and returns the raw body the stub received. */
        fun send(
            client: ChatClient,
            streaming: Boolean,
        ): String {
            stub.reset()
            stub.streaming = streaming
            val request = client.prompt(prompt()).toolCallbacks(weatherTool)
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
            val bodies = stub.requestBodies(ProviderStubServer.OPENAI_PATH)
            withClue("expected exactly one POST ${ProviderStubServer.OPENAI_PATH} to reach the stub") { bodies shouldHaveSize 1 }
            return bodies.single()
        }

        fun JsonNode.message(role: String): JsonNode = get("messages").single { it.get("role").asText() == role }

        fun JsonNode.cacheControlCount(): Int =
            (if (has("cache_control")) 1 else 0) + elements().asSequence().sumOf { it.cacheControlCount() }

        fun assertOneHourMarkers(body: String) {
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
            request.get("model").asText() shouldBe "anthropic/claude-sonnet-4-5"
            request.get("max_completion_tokens").asInt() shouldBe 1024
            request.get("tools").size() shouldBe 1
        }

        listOf(false, true).forEach { streaming ->
            val mode = if (streaming) "streaming (WebClient)" else "blocking (RestClient)"

            "$mode, Requesty with 1h: the body carries 1h markers and turns automatic caching off" {
                val body = send(chatClient(PromptCacheTtl.ONE_HOUR, requesty = true), streaming)

                assertOneHourMarkers(body)
                (mapper.readTree(body).get("stream")?.asBoolean() ?: false) shouldBe streaming
            }

            "$mode, Requesty with 1h and usage tracking disabled: the body carries 1h markers" {
                assertOneHourMarkers(send(chatClient(PromptCacheTtl.ONE_HOUR, requesty = true, usageTracking = false), streaming))
            }

            "$mode, Requesty with 5m: the body is byte-for-byte the one sent without the setting" {
                val baseline = send(chatClient(PromptCacheTtl.FIVE_MINUTES, requesty = false), streaming)
                val fiveMinutes = send(chatClient(PromptCacheTtl.FIVE_MINUTES, requesty = true), streaming)

                fiveMinutes shouldBe baseline
                withClue("no cache field expected with 5m, body was: $fiveMinutes") {
                    fiveMinutes.contains("cache_control") shouldBe false
                    fiveMinutes.contains("requesty") shouldBe false
                }
            }

            "$mode, another provider with 1h: the body is byte-for-byte the one sent without the setting" {
                val baseline = send(chatClient(PromptCacheTtl.FIVE_MINUTES, requesty = false), streaming)
                val otherProvider = send(chatClient(PromptCacheTtl.ONE_HOUR, requesty = false), streaming)

                otherProvider shouldBe baseline
                otherProvider.contains("cache_control") shouldBe false
            }
        }
    })
