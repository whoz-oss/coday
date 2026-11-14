package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.ErrorEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import kotlinx.coroutines.flow.toList
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.tool.ToolCallback
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Tests that verify streaming provider errors emit useful diagnostics: the status line, the request summary
 * (provider, model, message and tool counts, never the content) and, depending on
 * `agentos.ai-provider-errors.expose-response-body`, the masked provider response body.
 */
class AgentSimpleProviderErrorUnitSpec : StringSpec({
    timeout = 5000

    fun makeAgent(
        agentId: UUID,
        chatClient: ChatClient,
        exposeBody: Boolean = false,
        tools: Collection<StandardTool<*>> = emptyList(),
    ): AgentSimple =
        AgentSimple(
            metadata = EntityMetadata(id = agentId),
            name = "TestAgent",
            chatClient = chatClient,
            tools = tools,
            llmProvider = "openai",
            llmModel = "gpt-4o",
            exposeProviderResponseBody = exposeBody,
        )

    fun namedTool(toolName: String): StandardTool<*> {
        val tool = mockk<StandardTool<*>>(relaxed = true)
        every { tool.name } returns toolName
        every { tool.description } returns "desc of $toolName"
        every { tool.inputSchema } returns """{"type":"object"}"""
        return tool
    }

    fun failingChatClient(error: Throwable): ChatClient {
        val mockChatClient = mockk<ChatClient>(relaxed = true)
        val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
        every { mockChatClient.prompt(any<Prompt>()).toolCallbacks(any<List<ToolCallback>>()).stream() } returns mockStreamSpec
        every { mockStreamSpec.chatResponse() } returns Flux.error(error)
        return mockChatClient
    }

    fun userMessage(
        namespaceId: UUID,
        caseId: UUID,
    ) = MessageEvent(
        namespaceId = namespaceId,
        caseId = caseId,
        actor = Actor("user1", "User", ActorRole.USER),
        content = listOf(MessageContent.Text("hello")),
    )

    fun webClientException(
        status: HttpStatus,
        body: String,
    ): WebClientResponseException =
        WebClientResponseException(
            status.value(),
            status.reasonPhrase,
            org.springframework.http.HttpHeaders.EMPTY,
            body.toByteArray(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8,
        )

    // -----------------------------------------------------------------------
    // 4xx bare — emitted as ErrorEvent (not WarnEvent) without body in message
    // -----------------------------------------------------------------------

    "streaming 400 emits a diagnostic ErrorEvent without exposing the response body" {
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        val body = """{"error":{"type":"invalid_request_error","message":"tools.9.custom.name: String should match pattern '^[a-zA-Z0-9_-]{1,128}$'"}}"""
        val ex = webClientException(HttpStatus.BAD_REQUEST, body)

        val mockChatClient = mockk<ChatClient>(relaxed = true)
        val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
        every { mockStreamSpec.chatResponse() } returns Flux.error(ex)

        val events = makeAgent(agentId, mockChatClient)
            .run(listOf(userMessage(namespaceId, caseId)))
            .toList()

        // Must emit ErrorEvent (not WarnEvent) so the case lifecycle terminates correctly
        val errorEvent = events.filterIsInstance<ErrorEvent>().firstOrNull()
        errorEvent shouldNotBe null
        errorEvent!!.message shouldContain "AI provider"
        errorEvent.message shouldContain "400 BAD_REQUEST"
        errorEvent.message shouldNotContain body
        errorEvent.message shouldNotContain "tools.9.custom.name"

        // Must NOT emit a WarnEvent for a provider error (WarnEvent = generic path)
        events.filterIsInstance<WarnEvent>().size shouldBe 0

        // Run must still terminate
        events.filterIsInstance<AgentFinishedEvent>().size shouldBe 1
    }

    // -----------------------------------------------------------------------
    // 4xx Reactor-wrapped — the real production path
    // -----------------------------------------------------------------------

    "streaming 400 wrapped by Reactor emits a diagnostic ErrorEvent without exposing the response body" {
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        val body = """{"error":{"type":"invalid_request_error","message":"bad tool name"}}"""
        val raw = webClientException(HttpStatus.BAD_REQUEST, body)
        // Simulate Reactor wrapping: this is what actually arrives at the catch block
        val wrapped = Exceptions.propagate(raw)

        val mockChatClient = mockk<ChatClient>(relaxed = true)
        val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
        every { mockStreamSpec.chatResponse() } returns Flux.error(wrapped)

        val events = makeAgent(agentId, mockChatClient)
            .run(listOf(userMessage(namespaceId, caseId)))
            .toList()

        val errorEvent = events.filterIsInstance<ErrorEvent>().firstOrNull()
        errorEvent shouldNotBe null
        errorEvent!!.message shouldContain "AI provider"
        errorEvent.message shouldContain "400 BAD_REQUEST"
        errorEvent.message shouldNotContain body
        errorEvent.message shouldNotContain "bad tool name"
        events.filterIsInstance<WarnEvent>().size shouldBe 0
        events.filterIsInstance<AgentFinishedEvent>().size shouldBe 1
    }

    // -----------------------------------------------------------------------
    // 5xx — becomes TransientAiException path — still ErrorEvent
    // -----------------------------------------------------------------------

    "streaming 503 emits a transient diagnostic ErrorEvent without exposing the response body" {
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        val body = """{"error":"service unavailable"}"""
        val ex = webClientException(HttpStatus.SERVICE_UNAVAILABLE, body)

        val mockChatClient = mockk<ChatClient>(relaxed = true)
        val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
        every { mockStreamSpec.chatResponse() } returns Flux.error(ex)

        val events = makeAgent(agentId, mockChatClient)
            .run(listOf(userMessage(namespaceId, caseId)))
            .toList()

        val errorEvent = events.filterIsInstance<ErrorEvent>().firstOrNull()
        errorEvent shouldNotBe null
        errorEvent!!.message shouldContain "AI provider"
        errorEvent.message shouldContain "transient error"
        errorEvent.message shouldContain "503 SERVICE_UNAVAILABLE"
        errorEvent.message shouldNotContain body
        errorEvent.message shouldNotContain "service unavailable"
        events.filterIsInstance<WarnEvent>().size shouldBe 0
        events.filterIsInstance<AgentFinishedEvent>().size shouldBe 1
    }

    // -----------------------------------------------------------------------
    // exposeProviderResponseBody = true, request summary
    // -----------------------------------------------------------------------

    "streaming 400 with the body exposed puts the masked response body and the request summary in the ErrorEvent" {
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val body = """{"error":{"message":"tools.9.custom.name: invalid"},"api_key":"hunter2"}"""
        val chatClient = failingChatClient(Exceptions.propagate(webClientException(HttpStatus.BAD_REQUEST, body)))

        val events =
            makeAgent(UUID.randomUUID(), chatClient, exposeBody = true, tools = listOf(namedTool("jira__search"), namedTool("bad name")))
                .run(listOf(userMessage(namespaceId, caseId)))
                .toList()

        val errorEvent = events.filterIsInstance<ErrorEvent>().single()
        errorEvent.message shouldContain "400 BAD_REQUEST"
        errorEvent.message shouldContain "provider response:"
        errorEvent.message shouldContain "tools.9.custom.name: invalid"
        errorEvent.message shouldNotContain "hunter2"
        errorEvent.message shouldContain "request: provider=openai, model=gpt-4o, messages=1, tools=2 (jira__search, bad name)"
        events.filterIsInstance<WarnEvent>().size shouldBe 0
        events.filterIsInstance<AgentFinishedEvent>().size shouldBe 1
    }

    "streaming 503 with the body exposed puts the response body and the request summary in the transient ErrorEvent" {
        val chatClient = failingChatClient(webClientException(HttpStatus.SERVICE_UNAVAILABLE, "upstream overloaded"))

        val events =
            makeAgent(UUID.randomUUID(), chatClient, exposeBody = true)
                .run(listOf(userMessage(UUID.randomUUID(), UUID.randomUUID())))
                .toList()

        val errorEvent = events.filterIsInstance<ErrorEvent>().single()
        errorEvent.message shouldContain "transient error"
        errorEvent.message shouldContain "provider response: upstream overloaded"
        errorEvent.message shouldContain "request: provider=openai, model=gpt-4o, messages=1, tools=0"
    }

    "streaming 400 with the body hidden still carries the request summary and never the conversation" {
        val chatClient = failingChatClient(webClientException(HttpStatus.BAD_REQUEST, "private body"))

        val events =
            makeAgent(UUID.randomUUID(), chatClient, exposeBody = false)
                .run(listOf(userMessage(UUID.randomUUID(), UUID.randomUUID())))
                .toList()

        val errorEvent = events.filterIsInstance<ErrorEvent>().single()
        errorEvent.message shouldNotContain "private body"
        errorEvent.message shouldNotContain "provider response"
        errorEvent.message shouldContain "request: provider=openai, model=gpt-4o, messages=1, tools=0"
        errorEvent.message shouldNotContain "hello"
    }

    "a blocking-path NonTransientAiException already carrying the body is not given the body twice" {
        val ex = org.springframework.ai.retry.NonTransientAiException("HTTP 400 - {\"error\":\"spring body\"}")
        val chatClient = failingChatClient(ex)

        val events =
            makeAgent(UUID.randomUUID(), chatClient, exposeBody = true)
                .run(listOf(userMessage(UUID.randomUUID(), UUID.randomUUID())))
                .toList()

        val message = events.filterIsInstance<ErrorEvent>().single().message
        message.split("spring body").size - 1 shouldBe 1
        message shouldContain "request: provider=openai, model=gpt-4o"
    }

    // -----------------------------------------------------------------------
    // Non-HTTP exception — must still produce WarnEvent (original behaviour)
    // -----------------------------------------------------------------------

    "streaming generic RuntimeException still produces WarnEvent (no regression)" {
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        val mockChatClient = mockk<ChatClient>(relaxed = true)
        val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
        every { mockStreamSpec.chatResponse() } returns Flux.error(RuntimeException("some generic error"))

        val events = makeAgent(agentId, mockChatClient)
            .run(listOf(userMessage(namespaceId, caseId)))
            .toList()

        // Generic exceptions produce WarnEvent (not ErrorEvent)
        events.filterIsInstance<WarnEvent>().firstOrNull() shouldNotBe null
        events.filterIsInstance<AgentFinishedEvent>().size shouldBe 1
    }
})
