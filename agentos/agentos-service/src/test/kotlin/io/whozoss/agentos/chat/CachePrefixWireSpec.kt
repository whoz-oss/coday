package io.whozoss.agentos.chat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.agent.AgentSimple
import io.whozoss.agentos.agent.LastLlmRequestCapture
import io.whozoss.agentos.agent.LastLlmRequestRegistry
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseSideQuestionService
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.TransientCaseEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.flow.toList
import java.net.InetSocketAddress
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A side question appends to the agent's last request: everything before what it appends is sent again exactly
 * (same tools in the same order, same system, same messages), so the provider's prompt cache can serve it.
 *
 * The real chain end to end: [ChatModelFactory] and [ChatClientProvider], an [AgentSimple] running two tool rounds
 * (tools returning text) then answering, [LastLlmRequestCapture] recording its last request, and
 * [CaseSideQuestionService] asking. Only the provider is a local stub HTTP server, whose raw request bodies are
 * compared.
 */
class CachePrefixWireSpec :
    StringSpec({
        val stub = ScriptedProvider().also { it.start() }
        val mapper = ObjectMapper()

        afterSpec { stub.stop() }
        beforeTest { stub.reset() }

        val toolsRun = AtomicInteger()

        fun tool(
            name: String,
            output: String,
        ) = object : StandardTool<Nothing> {
            override val name = name
            override val description = "The $name tool"
            override val inputSchema = """{"type":"object","properties":{"q":{"type":"string"}}}"""
            override val version = "1"
            override val paramType: Class<Nothing>? = null

            override suspend fun execute(
                input: Nothing?,
                context: ToolContext,
            ) = ToolExecutionResult.success(output).also { toolsRun.incrementAndGet() }
        }

        /** The agent's turn then the side question, through the real chain; the raw bodies the provider received. */
        suspend fun agentTurnThenSideQuestion(apiType: AiApiType): List<JsonNode> {
            toolsRun.set(0)
            // The agent: two tool rounds, then its answer. The side question: an answer.
            stub.script(listOf(Reply.Tool("lookup"), Reply.Tool("search"), Reply.Text("All green."), Reply.Text("Two tools ran.")))
            val usageConfig = UsageConfigProperties(enabled = true)
            val factory = ChatModelFactory(ObservationRegistry.NOOP, AnthropicProperties(promptCachingEnabled = true), usageConfig)
            val clients = ChatClientProvider(factory, usageConfig = usageConfig)
            val namespaceId = UUID.randomUUID()
            val provider =
                AiProvider(
                    metadata = EntityMetadata(),
                    namespaceId = namespaceId,
                    name = "stub",
                    apiType = apiType,
                    baseUrl = stub.baseUrl,
                    apiKey = "sk-test",
                )
            val model =
                AiModel(
                    metadata = EntityMetadata(),
                    aiProviderId = provider.id,
                    apiModelName = "test-model",
                    temperature = 0.3,
                    maxCompletionTokens = 1024,
                )
            val case = Case(metadata = EntityMetadata(), namespaceId = namespaceId)
            val registry = LastLlmRequestRegistry()
            val capture = LastLlmRequestCapture(registry, namespaceId, null, model, provider, "planner", UUID.randomUUID())
            val agent =
                AgentSimple(
                    name = "planner",
                    chatClient = clients.getChatClient(model, provider, case.id.toString(), UsageAccumulator(), exchangeRecorder = capture.recorder),
                    tools = listOf(tool("lookup", "Build #42\nThe build is green."), tool("search", "No open incident.")),
                    systemPrompt = "Namespace: engineering.",
                    instructions = "You are the planner.",
                    llmProvider = provider.name,
                    llmModel = model.apiModelName,
                    lastExchange = capture::lastExchange,
                    onFinalRequest = capture::onFinalRequest,
                )
            val question =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = case.id,
                    actor = Actor("user", "User", ActorRole.USER),
                    content = listOf(MessageContent.Text("What is the status?")),
                )
            val persisted = listOf(question) + agent.run(listOf(question)) { true }.toList().filterNot { it is TransientCaseEvent }

            val sideQuestions =
                CaseSideQuestionService(
                    mockk { every { findById(case.id, false) } returns case },
                    mockk { every { findByParent(case.id) } returns persisted },
                    mockk { every { findById(model.id, any()) } returns model },
                    mockk { every { getById(provider.id) } returns provider },
                    clients,
                    registry,
                    mockk(relaxed = true),
                    mockk(),
                    Clock.systemUTC(),
                )
            sideQuestions.answer(case.id, "Which tools ran?") shouldBe "Two tools ran."
            toolsRun.get() shouldBe 2
            return stub.bodies().map(mapper::readTree)
        }

        /** [node] without its `cache_control` markers, which say where to cache, not what is cached. */
        fun withoutCacheMarkers(node: JsonNode): JsonNode =
            node.deepCopy<JsonNode>().also { copy ->
                fun strip(current: JsonNode) {
                    if (current is ObjectNode) current.remove("cache_control")
                    current.elements().forEach(::strip)
                }
                strip(copy)
            }

        /** What a request sends besides its content, the transport of the response aside. */
        fun JsonNode.optionsBesidesStreaming(): JsonNode =
            (deepCopy<JsonNode>() as ObjectNode).apply {
                remove(listOf("messages", "system", "tools", "stream", "stream_options"))
            }

        "OpenAI-compatible (Requesty): the side question's request starts with the agent's last request, unchanged" {
            val bodies = agentTurnThenSideQuestion(AiApiType.OpenAI)

            bodies shouldHaveSize 4
            val agentLast = bodies[2]
            val sideQuestion = bodies[3]
            val agentMessages = agentLast["messages"] as ArrayNode
            agentMessages.map { it["role"].asText() } shouldBe listOf("system", "user", "assistant", "tool", "assistant", "tool")
            agentMessages[3]["content"].asText() shouldContain "The build is green."
            // The same messages first, then only what the side question appends.
            val sideMessages = sideQuestion["messages"] as ArrayNode
            sideMessages.size() shouldBe agentMessages.size() + 2
            (0 until agentMessages.size()).forEach { index -> sideMessages[index] shouldBe agentMessages[index] }
            sideMessages[agentMessages.size()]["role"].asText() shouldBe "assistant"
            sideMessages[agentMessages.size()]["content"].asText() shouldBe "All green."
            sideMessages.last()["content"].asText() shouldContain "Which tools ran?"
            // Same tools, same order.
            sideQuestion["tools"] shouldBe agentLast["tools"]
            sideQuestion["tools"].map { it["function"]["name"].asText() } shouldBe listOf("lookup", "search")
            // The options are the same; only the transport of the response differs, and it is not part of the prefix.
            sideQuestion.optionsBesidesStreaming() shouldBe agentLast.optionsBesidesStreaming()
            agentLast["stream"].asBoolean() shouldBe true
            agentLast["stream_options"] shouldNotBe null
            (sideQuestion["stream"]?.asBoolean() ?: false) shouldBe false
            sideQuestion["stream_options"] shouldBe null
            (sideQuestion["max_tokens"] ?: sideQuestion["max_completion_tokens"]) shouldNotBe null
        }

        "Anthropic: the same tools, system and messages first; only the cache markers move to the new last message" {
            val bodies = agentTurnThenSideQuestion(AiApiType.Anthropic)

            bodies shouldHaveSize 4
            val agentLast = bodies[2]
            val sideQuestion = bodies[3]
            withoutCacheMarkers(sideQuestion["tools"]) shouldBe withoutCacheMarkers(agentLast["tools"])
            withoutCacheMarkers(sideQuestion["system"]) shouldBe withoutCacheMarkers(agentLast["system"])
            val agentMessages = agentLast["messages"] as ArrayNode
            agentMessages.map { it["role"].asText() } shouldBe listOf("user", "assistant", "user", "assistant", "user")
            agentMessages.toString() shouldContain "The build is green."
            val sideMessages = sideQuestion["messages"] as ArrayNode
            sideMessages.size() shouldBe agentMessages.size() + 2
            (0 until agentMessages.size()).forEach { index ->
                withoutCacheMarkers(sideMessages[index]) shouldBe withoutCacheMarkers(agentMessages[index])
            }
            sideQuestion.optionsBesidesStreaming() shouldBe agentLast.optionsBesidesStreaming()
            agentLast["stream"].asBoolean() shouldBe true
            (sideQuestion["stream"]?.asBoolean() ?: false) shouldBe false
        }
    })

/** What the stub answers to one request. */
private sealed interface Reply {
    data class Tool(
        val name: String,
    ) : Reply

    data class Text(
        val text: String,
    ) : Reply
}

/** A local provider answering each request with the next scripted [Reply], streamed or not as asked. */
private class ScriptedProvider {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val bodies = CopyOnWriteArrayList<String>()
    private val replies = ConcurrentLinkedQueue<Reply>()
    private val mapper = ObjectMapper()
    private val calls = AtomicInteger()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/v1/chat/completions") { exchange -> handle(exchange, anthropic = false) }
        server.createContext("/v1/messages") { exchange -> handle(exchange, anthropic = true) }
    }

    fun start() = server.start()

    fun stop() = server.stop(0)

    fun reset() {
        bodies.clear()
        replies.clear()
    }

    fun script(script: List<Reply>) {
        replies.addAll(script)
    }

    fun bodies(): List<String> = bodies.toList()

    private fun handle(
        exchange: HttpExchange,
        anthropic: Boolean,
    ) {
        exchange.use {
            val body = String(it.requestBody.readBytes(), Charsets.UTF_8)
            bodies += body
            val streaming = mapper.readTree(body)["stream"]?.asBoolean() == true
            val reply = replies.poll() ?: Reply.Text("unscripted")
            val id = calls.incrementAndGet()
            val response =
                when {
                    anthropic && streaming -> anthropicSse(reply, id)
                    anthropic -> anthropicJson(reply, id)
                    streaming -> openAiSse(reply, id)
                    else -> openAiJson(reply, id)
                }
            val bytes = response.toByteArray(Charsets.UTF_8)
            it.responseHeaders.add("Content-Type", if (streaming) "text/event-stream" else "application/json")
            it.sendResponseHeaders(200, bytes.size.toLong())
            it.responseBody.use { out -> out.write(bytes) }
        }
    }

    private fun openAiSse(
        reply: Reply,
        id: Int,
    ): String {
        val delta =
            when (reply) {
                is Reply.Tool -> {
                    """{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call-$id","type":"function",""" +
                        """"function":{"name":"${reply.name}","arguments":"{}"}}]}"""
                }

                is Reply.Text -> {
                    """{"role":"assistant","content":"${reply.text}"}"""
                }
            }
        val finish = if (reply is Reply.Tool) "tool_calls" else "stop"
        return listOf(
            """{"id":"c$id","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":$delta,"finish_reason":"$finish"}]}""",
            """{"id":"c$id","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}""",
            "[DONE]",
        ).joinToString("") { "data: $it\n\n" }
    }

    private fun openAiJson(
        reply: Reply,
        id: Int,
    ): String {
        val text = (reply as? Reply.Text)?.text ?: ""
        return """{"id":"c$id","object":"chat.completion","created":1,"model":"test-model","choices":[{"index":0,""" +
            """"message":{"role":"assistant","content":"$text"},"finish_reason":"stop"}],""" +
            """"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}"""
    }

    private fun anthropicSse(
        reply: Reply,
        id: Int,
    ): String {
        val start =
            """{"type":"message_start","message":{"id":"msg_$id","type":"message","role":"assistant","model":"test-model",""" +
                """"content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1,""" +
                """"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}"""
        val (block, delta, stop) =
            when (reply) {
                is Reply.Tool -> {
                    Triple(
                        """{"type":"tool_use","id":"toolu_$id","name":"${reply.name}","input":{}}""",
                        """{"type":"input_json_delta","partial_json":"{}"}""",
                        "tool_use",
                    )
                }

                is Reply.Text -> {
                    Triple("""{"type":"text","text":""}""", """{"type":"text_delta","text":"${reply.text}"}""", "end_turn")
                }
            }
        return listOf(
            "message_start" to start,
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":$block}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":$delta}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"$stop","stop_sequence":null},"usage":{"output_tokens":2}}""",
            "message_stop" to """{"type":"message_stop"}""",
        ).joinToString("") { (event, data) -> "event: $event\ndata: $data\n\n" }
    }

    private fun anthropicJson(
        reply: Reply,
        id: Int,
    ): String {
        val text = (reply as? Reply.Text)?.text ?: ""
        return """{"id":"msg_$id","type":"message","role":"assistant","model":"test-model",""" +
            """"content":[{"type":"text","text":"$text"}],"stop_reason":"end_turn","stop_sequence":null,""" +
            """"usage":{"input_tokens":10,"output_tokens":2,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}"""
    }
}
