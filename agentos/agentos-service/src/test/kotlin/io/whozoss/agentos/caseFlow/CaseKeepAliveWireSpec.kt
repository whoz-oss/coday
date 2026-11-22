package io.whozoss.agentos.caseFlow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.agent.FinalLlmRequest
import io.whozoss.agentos.agent.LastLlmRequest
import io.whozoss.agentos.agent.LastLlmRequestCapture
import io.whozoss.agentos.agent.LastLlmRequestRegistry
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.AnthropicProperties
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.chat.ChatModelFactory
import io.whozoss.agentos.chat.ProviderStubServer
import io.whozoss.agentos.chat.RequestyPromptCacheProperties
import io.whozoss.agentos.chat.UsageAccumulator
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.aiProvider.ModelPricing
import io.whozoss.agentos.sdk.api.user.PromptCacheTtl
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.usage.RunCostService
import io.whozoss.agentos.usage.RunCostService.NonPausingRegistration
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
import java.time.ZoneId
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
        val claude =
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
            val factory =
                ChatModelFactory(
                    ObservationRegistry.NOOP,
                    AnthropicProperties(promptCachingEnabled = true),
                    usageConfig,
                    RequestyPromptCacheProperties(),
                ) { requesty }
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

        class Turn(
            val registry: LastLlmRequestRegistry,
            val request: LastLlmRequest,
        )

        /**
         * Sends the agent's last request as AgentSimple does (streamed, through [LastLlmRequestCapture]) and returns
         * what the registry holds of it.
         */
        fun agentTurn(
            clients: ChatClientProvider,
            model: AiModel,
            caseId: UUID,
            ttl: PromptCacheTtl,
            tools: List<ToolCallback>,
            clock: Clock = Clock.systemUTC(),
        ): Turn {
            stub.streaming = true
            val registry = LastLlmRequestRegistry(clock)
            val capture =
                LastLlmRequestCapture(
                    registry,
                    namespaceId,
                    userId,
                    model,
                    provider,
                    "planner",
                    agentConfigId,
                    generation = registry.invalidate(caseId),
                    promptCacheTtl = ttl,
                )
            clients
                .getChatClient(model, provider, caseId.toString(), UsageAccumulator(), promptCacheTtl = ttl, exchangeRecorder = capture.recorder)
                .prompt(Prompt(conversation))
                .toolCallbacks(tools)
                .stream()
                .content()
                .collectList()
                .block(Duration.ofSeconds(30))!!
                .joinToString("") shouldBe "Done"
            stub.streaming = false
            val exchange = capture.lastExchange()!!
            capture.onFinalRequest(
                FinalLlmRequest(
                    caseId = caseId,
                    messages = exchange.messages,
                    toolDefinitions = tools.map { it.toolDefinition },
                    finalText = exchange.responseText!!,
                    coveredEventCount = 3,
                    lastCoveredEventId = UUID.randomUUID(),
                    sentAt = exchange.sentAt,
                ),
            )
            return Turn(registry, registry.get(caseId)!!)
        }

        fun records(): Pair<UsageRecordService, MutableList<UsageRecord>> {
            val records = CopyOnWriteArrayList<UsageRecord>()
            val service = mockk<UsageRecordService> { every { create(any()) } answers { firstArg<UsageRecord>().also(records::add) } }
            return service to records
        }

        /** The pinger, resolving [model] and [provider] again by id; the cost guard admits it without a session. */
        fun pinger(
            clients: ChatClientProvider,
            model: AiModel,
            usageRecords: UsageRecordService,
        ): CaseKeepAlivePinger {
            val registration =
                mockk<RunCostService.Registration> { every { finish(any()) } answers { firstArg<() -> Unit>().invoke() } }
            return CaseKeepAlivePinger(
                clients,
                mockk<AiModelService> { every { findById(model.id, any()) } returns model },
                mockk<AiProviderService> {
                    every { getById(provider.id) } returns provider
                    every { resolveProvider(namespaceId, userId, provider.name) } returns provider
                },
                usageRecords,
                mockk<RunCostService> {
                    every { registerNonPausing(any(), any(), false) } returns NonPausingRegistration.Registered(registration)
                },
                UsageConfigProperties(enabled = true),
            )
        }

        fun assertReplays(
            agentBody: String,
            pingBody: String,
            limit: String = "max_tokens",
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
            withClue("the ping must be limited to 1 output token with $limit only, body was: $pingBody") {
                ping.get(limit)?.asInt() shouldBe 1
                listOf("max_tokens", "max_completion_tokens").filter { it != limit && ping.has(it) } shouldBe emptyList()
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
            val clock = MutableClock(Instant.parse("2026-01-01T10:00:00Z"))
            val turn = agentTurn(clients, claude, caseId, PromptCacheTtl.EXTENDED, tools, clock)
            val case = Case(metadata = EntityMetadata(id = caseId), namespaceId = namespaceId, status = CaseStatus.IDLE, keepAlive = true)
            val caseService =
                mockk<CaseService> {
                    every { findByIds(listOf(caseId), false) } returns listOf(case)
                    every { findById(caseId, false) } returns case
                }
            val (usageRecords, records) = records()
            val scheduler =
                CaseKeepAliveScheduler(
                    registry = turn.registry,
                    caseService = caseService,
                    pinger = pinger(clients, claude, usageRecords),
                    keepAliveCaseIds = mockk { every { current() } returns setOf(caseId) },
                    chatModelFactory =
                        ChatModelFactory(
                            ObservationRegistry.NOOP,
                            AnthropicProperties(promptCachingEnabled = true),
                            UsageConfigProperties(enabled = true),
                            RequestyPromptCacheProperties(),
                        ) { true },
                    aiModelService = mockk { every { findById(claude.id, any()) } returns claude },
                    properties = CaseKeepAliveProperties(),
                    clock = clock,
                    usageConfig = UsageConfigProperties(enabled = true),
                ).apply { executor = Executor { it.run() } }

            // A 1-hour cache, no lifetime on the model: not due at 4:20, due at 58:00.
            clock.now = turn.request.lastUsedAt.plus(Duration.ofMinutes(4).plusSeconds(20))
            scheduler.refreshDue() shouldBe emptyList()
            clock.now = turn.request.lastUsedAt.plus(Duration.ofMinutes(58))
            scheduler.refreshDue() shouldBe listOf(caseId)

            val bodies = stub.requestBodies(ProviderStubServer.OPENAI_PATH)
            bodies shouldHaveSize 2
            assertReplays(agentBody = bodies[0], pingBody = bodies[1])
            // The one-hour rewrite marked both the same way.
            mapper.readTree(bodies[1]).cacheControlCount() shouldBe mapper.readTree(bodies[0]).cacheControlCount()
            mapper.readTree(bodies[1]).get("requesty").get("auto_cache").asBoolean() shouldBe false
            executed.get() shouldBe 0
            turn.registry.get(caseId)!!.lastCacheTouchAt shouldBe clock.instant()
            val record = records.single()
            record.source shouldBe UsageSource.KEEP_ALIVE
            record.outcome shouldBe UsageOutcome.COMPLETED
            record.caseId shouldBe caseId
            record.namespaceId shouldBe namespaceId
            record.agentName shouldBe "planner"
            record.agentConfigId shouldBe agentConfigId
            record.userId shouldBe userId
            record.providerName shouldBe provider.name
            record.apiModelName shouldBe claude.apiModelName
            record.inputTokens shouldBe 10
        }

        "a ping of a 5-minute conversation replays the agent's last request without any cache marker" {
            val clients = clients(requesty = false)
            val executed = AtomicInteger()
            val tools = listOf(tool("lookup", executed))
            val caseId = UUID.randomUUID()
            val turn = agentTurn(clients, claude, caseId, PromptCacheTtl.STANDARD, tools)
            val case = Case(metadata = EntityMetadata(id = caseId), namespaceId = namespaceId, status = CaseStatus.IDLE, keepAlive = true)
            val (usageRecords, records) = records()

            pinger(clients, claude, usageRecords).ping(case, turn.request).shouldBeInstanceOf<CaseKeepAlivePinger.Result.Sent>()

            val bodies = stub.requestBodies(ProviderStubServer.OPENAI_PATH)
            bodies shouldHaveSize 2
            assertReplays(agentBody = bodies[0], pingBody = bodies[1])
            bodies[1].contains("cache_control") shouldBe false
            executed.get() shouldBe 0
            records.single().source shouldBe UsageSource.KEEP_ALIVE
        }

        "an OpenAI reasoning model gets max_completion_tokens 1 instead of max_tokens" {
            val clients = clients(requesty = false)
            val reasoning = claude.copy(metadata = EntityMetadata(id = UUID.randomUUID()), apiModelName = "openai/o4-mini")
            val caseId = UUID.randomUUID()
            val turn = agentTurn(clients, reasoning, caseId, PromptCacheTtl.STANDARD, listOf(tool("lookup", AtomicInteger())))
            val case = Case(metadata = EntityMetadata(id = caseId), namespaceId = namespaceId, status = CaseStatus.IDLE, keepAlive = true)

            pinger(clients, reasoning, records().first).ping(case, turn.request)

            val bodies = stub.requestBodies(ProviderStubServer.OPENAI_PATH)
            assertReplays(agentBody = bodies[0], pingBody = bodies[1], limit = "max_completion_tokens")
        }

        "a model or provider that changed since the agent's request is not pinged" {
            val clients = clients(requesty = false)
            val caseId = UUID.randomUUID()
            val turn = agentTurn(clients, claude, caseId, PromptCacheTtl.STANDARD, emptyList())
            val case = Case(metadata = EntityMetadata(id = caseId), namespaceId = namespaceId, status = CaseStatus.IDLE, keepAlive = true)
            val changed = claude.copy(temperature = 0.2)

            pinger(clients, changed, records().first).ping(case, turn.request) shouldBe CaseKeepAlivePinger.Result.OptionsChanged

            stub.requestBodies(ProviderStubServer.OPENAI_PATH) shouldHaveSize 1
        }
    })

/** A clock the test moves. */
private class MutableClock(
    var now: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}
