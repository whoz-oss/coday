package io.whozoss.agentos.caseFlow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.agent.LastLlmRequest
import io.whozoss.agentos.agent.LastLlmRequestRegistry
import io.whozoss.agentos.chat.AnthropicProperties
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.chat.ChatModelFactory
import io.whozoss.agentos.chat.ProviderStubServer
import io.whozoss.agentos.chat.UsageAccumulator
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.aiProvider.ModelPricing
import io.whozoss.agentos.sdk.api.user.PromptCacheTtl
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.usage.UsageOutcome
import io.whozoss.agentos.usage.UsageRecord
import io.whozoss.agentos.usage.UsageRecordService
import io.whozoss.agentos.usage.UsageSource
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * What a keep-alive ping puts on the wire, compared with the agent's last request it replays: the real
 * [ChatModelFactory] and [ChatClientProvider] (Spring AI's OpenAI serialization, the Requesty one-hour rewrite),
 * only the provider is a loopback [ProviderStubServer].
 *
 * The agent's request is sent the way [io.whozoss.agentos.agent.AgentSimple] sends it (streamed, with its tools,
 * the messages reported by `onRequest`); the ping must carry the very same messages, tools and model, and only
 * differ by a `max_tokens` of 1 on a blocking call.
 */
class CaseKeepAliveWireSpec :
    StringSpec({
        val stub = ProviderStubServer().also { it.start() }
        val mapper = ObjectMapper()

        afterSpec { stub.stop() }
        beforeTest { stub.reset() }

        val namespaceId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val agentConfigId = UUID.randomUUID()
        val providerId = UUID.randomUUID()
        val provider =
            AiProvider(
                metadata = EntityMetadata(id = providerId),
                namespaceId = namespaceId,
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
                pricing = ModelPricing(inputMTokens = 3.0, outputMTokens = 15.0),
            )

        fun clients(requesty: Boolean): ChatClientProvider {
            val usageConfig = UsageConfigProperties(enabled = true)
            val factory = ChatModelFactory(ObservationRegistry.NOOP, AnthropicProperties(promptCachingEnabled = true), usageConfig)
            if (requesty) factory.isRequestyBaseUrl = { true }
            return ChatClientProvider(factory, usageConfig = usageConfig)
        }

        fun tool(
            name: String,
            executed: AtomicInteger,
        ): ToolCallback =
            object : ToolCallback {
                private val definition =
                    ToolDefinition
                        .builder()
                        .name(name)
                        .description("The $name tool")
                        .inputSchema("""{"type":"object","properties":{"q":{"type":"string"}}}""")
                        .build()

                override fun getToolDefinition(): ToolDefinition = definition

                override fun call(input: String): String {
                    executed.incrementAndGet()
                    return "$name result"
                }
            }

        // The last request of a turn that ran a tool: the tool call and its result are in the history.
        val conversation: List<Message> =
            listOf(
                SystemMessage("You are a meticulous assistant."),
                UserMessage("What is the weather in Paris?"),
                AssistantMessage
                    .builder()
                    .content("")
                    .toolCalls(listOf(AssistantMessage.ToolCall("call-1", "function", "lookup", """{"q":"Paris"}""")))
                    .build(),
                ToolResponseMessage
                    .builder()
                    .responses(listOf(ToolResponseMessage.ToolResponse("call-1", "lookup", "sunny")))
                    .build(),
            )

        /** Sends the agent's request as AgentSimple does and returns what the registry would hold of it. */
        fun agentTurn(
            clients: ChatClientProvider,
            caseId: UUID,
            ttl: PromptCacheTtl,
            tools: List<ToolCallback>,
            capturedAt: Instant,
        ): LastLlmRequest {
            stub.streaming = true
            val sent = AtomicReference<List<Message>>()
            clients
                .getChatClient(model, provider, caseId.toString(), UsageAccumulator(), promptCacheTtl = ttl, onRequest = {
                    sent.set(it.instructions.toList())
                }).prompt(Prompt(conversation))
                .toolCallbacks(tools)
                .stream()
                .content()
                .collectList()
                .block(Duration.ofSeconds(30))!!
                .joinToString("") shouldBe "Done"
            stub.streaming = false
            return LastLlmRequest(
                caseId = caseId,
                messages = sent.get(),
                toolDefinitions = tools.map { it.toolDefinition },
                model = model,
                provider = provider,
                finalText = "Done",
                agentName = "planner",
                agentConfigId = agentConfigId,
                coveredEventCount = 3,
                lastCoveredEventId = UUID.randomUUID(),
                promptCacheTtl = ttl,
                userId = userId,
                capturedAt = capturedAt,
            )
        }

        fun records(): Pair<UsageRecordService, MutableList<UsageRecord>> {
            val records = CopyOnWriteArrayList<UsageRecord>()
            val service = mockk<UsageRecordService> { every { create(any()) } answers { firstArg<UsageRecord>().also(records::add) } }
            return service to records
        }

        fun assertReplays(
            agentBody: String,
            pingBody: String,
        ) {
            val agent = mapper.readTree(agentBody)
            val ping = mapper.readTree(pingBody)
            withClue("messages must be identical\nagent: $agentBody\nping:  $pingBody") {
                mapper.writeValueAsString(ping.get("messages")) shouldBe mapper.writeValueAsString(agent.get("messages"))
            }
            withClue("tools must be identical\nagent: $agentBody\nping:  $pingBody") {
                ping.get("tools") shouldNotBe null
                mapper.writeValueAsString(ping.get("tools")) shouldBe mapper.writeValueAsString(agent.get("tools"))
            }
            ping.get("model") shouldBe agent.get("model")
            ping.get("temperature") shouldBe agent.get("temperature")
            mapper.writeValueAsString(ping.get("requesty")) shouldBe mapper.writeValueAsString(agent.get("requesty"))
            withClue("the ping must be limited to 1 output token with max_tokens only, body was: $pingBody") {
                ping.get("max_tokens")?.asInt() shouldBe 1
                ping.has("max_completion_tokens") shouldBe false
            }
            withClue("the ping must not stream, body was: $pingBody") {
                (ping.get("stream")?.asBoolean() ?: false) shouldBe false
            }
            // The agent's request keeps its own limit and streams.
            agent.get("max_completion_tokens").asInt() shouldBe 1024
            agent.has("max_tokens") shouldBe false
            agent.get("stream").asBoolean() shouldBe true
        }

        fun JsonNode.cacheControlCount(): Int =
            (if (has("cache_control")) 1 else 0) + elements().asSequence().sumOf { it.cacheControlCount() }

        "the scheduler's ping of a 1-hour Requesty conversation replays the agent's last request with max_tokens 1" {
            val clients = clients(requesty = true)
            val executed = AtomicInteger()
            val tools = listOf(tool("lookup", executed), tool("search", executed))
            val caseId = UUID.randomUUID()
            val capturedAt = Instant.parse("2026-01-01T10:00:00Z")
            val lastRequest = agentTurn(clients, caseId, PromptCacheTtl.ONE_HOUR, tools, capturedAt)
            val clock = Clock.fixed(capturedAt.plus(CaseKeepAliveScheduler.ONE_HOUR_INTERVAL), ZoneOffset.UTC)
            val registry = LastLlmRequestRegistry(clock).apply { put(lastRequest) }
            val case = Case(metadata = EntityMetadata(id = caseId), namespaceId = namespaceId, status = CaseStatus.IDLE, keepAlive = true)
            val caseService = mockk<CaseService> { every { findByIds(listOf(caseId), false) } returns listOf(case) }
            val (usageRecords, records) = records()
            val scheduler =
                CaseKeepAliveScheduler(
                    registry = registry,
                    caseService = caseService,
                    pinger = CaseKeepAlivePinger(clients, usageRecords, usageConfig = UsageConfigProperties(enabled = true)),
                    properties = CaseKeepAliveProperties(),
                    clock = clock,
                    usageConfig = UsageConfigProperties(enabled = true),
                ).apply { executor = Executor { it.run() } }

            scheduler.refreshDue() shouldBe listOf(caseId)

            val bodies = stub.requestBodies(ProviderStubServer.OPENAI_PATH)
            bodies shouldHaveSize 2
            assertReplays(agentBody = bodies[0], pingBody = bodies[1])
            // The one-hour rewrite marked both the same way.
            mapper.readTree(bodies[1]).cacheControlCount() shouldBe 2
            mapper.readTree(bodies[1]).get("requesty").get("auto_cache").asBoolean() shouldBe false
            executed.get() shouldBe 0
            registry.get(caseId)!!.lastRefreshAt shouldBe clock.instant()
            val record = records.single()
            record.source shouldBe UsageSource.KEEP_ALIVE
            record.outcome shouldBe UsageOutcome.COMPLETED
            record.caseId shouldBe caseId
            record.namespaceId shouldBe namespaceId
            record.agentName shouldBe "planner"
            record.agentConfigId shouldBe agentConfigId
            record.userId shouldBe userId
            record.providerName shouldBe provider.name
            record.apiModelName shouldBe model.apiModelName
            record.inputTokens shouldBe 10
        }

        "a ping of a 5-minute conversation replays the agent's last request without any cache marker" {
            val clients = clients(requesty = false)
            val executed = AtomicInteger()
            val tools = listOf(tool("lookup", executed))
            val caseId = UUID.randomUUID()
            val lastRequest = agentTurn(clients, caseId, PromptCacheTtl.FIVE_MINUTES, tools, Instant.now())
            val case = Case(metadata = EntityMetadata(id = caseId), namespaceId = namespaceId, status = CaseStatus.IDLE, keepAlive = true)
            val (usageRecords, records) = records()

            CaseKeepAlivePinger(clients, usageRecords, usageConfig = UsageConfigProperties(enabled = true)).ping(case, lastRequest)

            val bodies = stub.requestBodies(ProviderStubServer.OPENAI_PATH)
            bodies shouldHaveSize 2
            assertReplays(agentBody = bodies[0], pingBody = bodies[1])
            bodies[1].contains("cache_control") shouldBe false
            executed.get() shouldBe 0
            records.single().source shouldBe UsageSource.KEEP_ALIVE
        }
    })
