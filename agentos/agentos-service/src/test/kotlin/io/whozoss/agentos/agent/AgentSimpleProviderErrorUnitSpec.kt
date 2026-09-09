package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
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
import kotlinx.coroutines.flow.toList
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Tests that verify the streaming-path provider error surfacing fix.
 *
 * When the LLM provider rejects a streaming request with an HTTP error, the response body
 * (containing the actionable error description) must appear in the [ErrorEvent] emitted —
 * exactly as it does on the blocking path via [org.springframework.ai.retry.NonTransientAiException].
 */
class AgentSimpleProviderErrorUnitSpec : StringSpec({
    timeout = 5000

    fun makeAgent(
        agentId: UUID,
        chatClient: ChatClient,
    ): AgentSimple =
        AgentSimple(
            metadata = EntityMetadata(id = agentId),
            name = "TestAgent",
            chatClient = chatClient,
            tools = emptyList(),
            llmProvider = "openai",
            llmModel = "gpt-4o",
        )

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
    // 4xx bare — emitted as ErrorEvent (not WarnEvent) with body in message
    // -----------------------------------------------------------------------

    "streaming 400 from provider surfaces as ErrorEvent containing the response body" {
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        val body = """{"error":{"type":"invalid_request_error","message":"tools.9.custom.name: String should match pattern '^[a-zA-Z0-9_-]{1,128}$'"}}"""
        val ex = webClientException(HttpStatus.BAD_REQUEST, body)

        val mockChatClient = mockk<ChatClient>(relaxed = true)
        val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
        every { mockStreamSpec.content() } returns Flux.error(ex)

        val events = makeAgent(agentId, mockChatClient)
            .run(listOf(userMessage(namespaceId, caseId)))
            .toList()

        // Must emit ErrorEvent (not WarnEvent) so the case lifecycle terminates correctly
        val errorEvent = events.filterIsInstance<ErrorEvent>().firstOrNull()
        errorEvent shouldNotBe null
        errorEvent!!.message shouldContain body

        // Must NOT emit a WarnEvent for a provider error (WarnEvent = generic path)
        events.filterIsInstance<WarnEvent>().size shouldBe 0

        // Run must still terminate
        events.filterIsInstance<AgentFinishedEvent>().size shouldBe 1
    }

    // -----------------------------------------------------------------------
    // 4xx Reactor-wrapped — the real production path
    // -----------------------------------------------------------------------

    "streaming 400 wrapped by Reactor surfaces as ErrorEvent containing the response body" {
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
        every { mockStreamSpec.content() } returns Flux.error(wrapped)

        val events = makeAgent(agentId, mockChatClient)
            .run(listOf(userMessage(namespaceId, caseId)))
            .toList()

        val errorEvent = events.filterIsInstance<ErrorEvent>().firstOrNull()
        errorEvent shouldNotBe null
        errorEvent!!.message shouldContain body
        events.filterIsInstance<WarnEvent>().size shouldBe 0
        events.filterIsInstance<AgentFinishedEvent>().size shouldBe 1
    }

    // -----------------------------------------------------------------------
    // 5xx — becomes TransientAiException path — still ErrorEvent
    // -----------------------------------------------------------------------

    "streaming 503 from provider surfaces as ErrorEvent (transient path)" {
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        val body = """{"error":"service unavailable"}"""
        val ex = webClientException(HttpStatus.SERVICE_UNAVAILABLE, body)

        val mockChatClient = mockk<ChatClient>(relaxed = true)
        val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
        every { mockStreamSpec.content() } returns Flux.error(ex)

        val events = makeAgent(agentId, mockChatClient)
            .run(listOf(userMessage(namespaceId, caseId)))
            .toList()

        val errorEvent = events.filterIsInstance<ErrorEvent>().firstOrNull()
        errorEvent shouldNotBe null
        errorEvent!!.message shouldContain body
        events.filterIsInstance<WarnEvent>().size shouldBe 0
        events.filterIsInstance<AgentFinishedEvent>().size shouldBe 1
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
        every { mockStreamSpec.content() } returns Flux.error(RuntimeException("some generic error"))

        val events = makeAgent(agentId, mockChatClient)
            .run(listOf(userMessage(namespaceId, caseId)))
            .toList()

        // Generic exceptions produce WarnEvent (not ErrorEvent)
        events.filterIsInstance<WarnEvent>().firstOrNull() shouldNotBe null
        events.filterIsInstance<AgentFinishedEvent>().size shouldBe 1
    }
})
