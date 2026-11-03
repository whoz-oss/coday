package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.ErrorEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.util.ContextWindowExceededException
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.toList
import mu.KLogger
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import java.nio.charset.StandardCharsets
import java.util.UUID

private const val OVERFLOW_BODY =
    """{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 213462 tokens > 200000 maximum"}}"""

/**
 * A context-window overflow is reported explicitly and flagged on the [AgentFinishedEvent], on every path
 * an agent can see it by: the direct catch of a [NonTransientAiException] (blocking call), the generic
 * catch (streaming, buried under Reactor), and both agents. Any other error is reported as before.
 */
class AgentContextOverflowUnitSpec :
    StringSpec({
        timeout = 5000

        val namespaceId: UUID = UUID.randomUUID()
        val caseId: UUID = UUID.randomUUID()
        val agentId: UUID = UUID.randomUUID()
        val logger = mockk<KLogger>(relaxed = true)

        fun mockAgent(): Agent =
            mockk {
                every { metadata } returns EntityMetadata(id = agentId)
                every { this@mockk.id } returns agentId
                every { name } returns "test-agent"
                every { llmProvider } returns "anthropic"
                every { llmModel } returns "claude-3"
            }

        suspend fun collect(block: suspend FlowCollector<CaseEvent>.() -> Unit): List<CaseEvent> {
            val emitted = mutableListOf<CaseEvent>()
            FlowCollector<CaseEvent> { emitted.add(it) }.block()
            return emitted
        }

        fun overflowException() =
            WebClientResponseException(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.reasonPhrase,
                HttpHeaders.EMPTY,
                OVERFLOW_BODY.toByteArray(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8,
            )

        // -----------------------------------------------------------------
        // emitProviderErrorAndFinishEvents
        // -----------------------------------------------------------------

        "an overflow emits an explicit ErrorEvent naming the model and the token counts, then a flagged AgentFinishedEvent" {
            val events =
                collect {
                    emitProviderErrorAndFinishEvents(
                        mockAgent(),
                        ContextWindowExceededException("Context window exceeded (213462 > 200000 tokens)", null, 213_462L, 200_000L),
                        namespaceId,
                        caseId,
                        logger,
                    )
                }

            events shouldHaveSize 2
            val error = events[0] as ErrorEvent
            error.message shouldContain "context window of model claude-3"
            error.message shouldContain "(213462 > 200000 tokens)"
            error.message shouldContain "Start a new case or ask for a shorter task"
            val finished = events[1] as AgentFinishedEvent
            finished.contextWindowExceeded shouldBe true
            finished.agentId shouldBe agentId
        }

        "an overflow without token counts omits them from the message" {
            val events =
                collect {
                    emitProviderErrorAndFinishEvents(
                        mockAgent(),
                        ContextWindowExceededException("Context window exceeded"),
                        namespaceId,
                        caseId,
                        logger,
                    )
                }

            val error = events.filterIsInstance<ErrorEvent>().single()
            error.message shouldContain "model claude-3 and the agent cannot continue"
            error.message shouldNotContain " > "
            events.filterIsInstance<AgentFinishedEvent>().single().contextWindowExceeded shouldBe true
        }

        "a blocking-call NonTransientAiException carrying an overflow body is classified, and its body stays out of the event" {
            val events =
                collect {
                    emitProviderErrorAndFinishEvents(
                        mockAgent(),
                        NonTransientAiException("400 - $OVERFLOW_BODY"),
                        namespaceId,
                        caseId,
                        logger,
                    )
                }

            val error = events.filterIsInstance<ErrorEvent>().single()
            error.message shouldContain "context window of model claude-3"
            error.message shouldContain "(213462 > 200000 tokens)"
            error.message shouldNotContain "prompt is too long"
            events.filterIsInstance<AgentFinishedEvent>().single().contextWindowExceeded shouldBe true
        }

        "any other NonTransientAiException is reported as before, without the flag" {
            val events =
                collect {
                    emitProviderErrorAndFinishEvents(
                        mockAgent(),
                        NonTransientAiException("400 BAD_REQUEST from POST https://api"),
                        namespaceId,
                        caseId,
                        logger,
                    )
                }

            events.filterIsInstance<ErrorEvent>().single().message shouldBe
                "The AI provider rejected the request and the agent cannot continue: 400 BAD_REQUEST from POST https://api"
            events.filterIsInstance<AgentFinishedEvent>().single().contextWindowExceeded shouldBe null
        }

        // -----------------------------------------------------------------
        // handleGenericAgentException
        // -----------------------------------------------------------------

        "a streaming overflow buried under Reactor is classified by the generic handler" {
            val events =
                collect {
                    handleGenericAgentException(
                        mockAgent(),
                        RuntimeException(Exceptions.propagate(overflowException())),
                        namespaceId,
                        caseId,
                        logger,
                    )
                }

            events.filterIsInstance<ErrorEvent>().single().message shouldContain "(213462 > 200000 tokens)"
            events.filterIsInstance<AgentFinishedEvent>().single().contextWindowExceeded shouldBe true
            events.filterIsInstance<WarnEvent>() shouldHaveSize 0
        }

        "an overflow NonTransientAiException wrapped in another exception is classified by the generic handler" {
            val events =
                collect {
                    handleGenericAgentException(
                        mockAgent(),
                        IllegalStateException("wrapper", NonTransientAiException("400 - $OVERFLOW_BODY")),
                        namespaceId,
                        caseId,
                        logger,
                    )
                }

            events.filterIsInstance<AgentFinishedEvent>().single().contextWindowExceeded shouldBe true
        }

        "an unrelated exception is reported as before by the generic handler, without the flag" {
            val events =
                collect {
                    handleGenericAgentException(mockAgent(), RuntimeException("some generic error"), namespaceId, caseId, logger)
                }

            events.filterIsInstance<WarnEvent>().single().message shouldContain "some generic error"
            events.filterIsInstance<AgentFinishedEvent>().single().contextWindowExceeded shouldBe null
        }

        // -----------------------------------------------------------------
        // Both agents
        // -----------------------------------------------------------------

        fun userMessage() =
            MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = Actor("user1", "User", ActorRole.USER),
                content = listOf(MessageContent.Text("hello")),
            )

        fun simpleAgentFailingWith(failure: Throwable): AgentSimple {
            val chatClient = mockk<ChatClient>(relaxed = true)
            val streamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { chatClient.prompt(any<Prompt>()).stream() } returns streamSpec
            every { streamSpec.chatResponse() } returns Flux.error(failure)
            return AgentSimple(
                metadata = EntityMetadata(id = agentId),
                name = "TestAgent",
                chatClient = chatClient,
                tools = emptyList(),
                llmProvider = "anthropic",
                llmModel = "claude-3",
            )
        }

        "AgentSimple: a streaming overflow (generic catch) is reported explicitly and flagged" {
            val events = simpleAgentFailingWith(overflowException()).run(listOf(userMessage())).toList()

            val error = events.filterIsInstance<ErrorEvent>().single()
            error.message shouldContain "context window of model claude-3"
            error.message shouldContain "(213462 > 200000 tokens)"
            error.message shouldNotContain "prompt is too long"
            events.filterIsInstance<AgentFinishedEvent>().single().contextWindowExceeded shouldBe true
        }

        "AgentSimple: an overflow NonTransientAiException (direct catch) is reported explicitly and flagged" {
            val events =
                simpleAgentFailingWith(NonTransientAiException("400 - $OVERFLOW_BODY")).run(listOf(userMessage())).toList()

            events.filterIsInstance<ErrorEvent>().single().message shouldContain "context window of model claude-3"
            events.filterIsInstance<AgentFinishedEvent>().single().contextWindowExceeded shouldBe true
        }

        "AgentSimple: another provider error stays unflagged" {
            val events =
                simpleAgentFailingWith(NonTransientAiException("400 - bad tool name")).run(listOf(userMessage())).toList()

            events.filterIsInstance<ErrorEvent>().single().message shouldContain "AI provider rejected"
            events.filterIsInstance<AgentFinishedEvent>().single().contextWindowExceeded shouldBe null
        }

        "AgentAdvanced: an overflow from the LLM during parameter generation is reported explicitly and flagged, with no loop" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } throws NonTransientAiException("400 - $OVERFLOW_BODY")
            val tool = mockk<StandardTool<String>>(relaxed = true)
            every { tool.name } returns "FILES__ReadFile"
            every { tool.description } returns "Read a file"
            every { tool.inputSchema } returns "{}"
            every { tool.paramType } returns String::class.java
            val generator = mockk<AgentIntentionGenerator>()
            every { generator.generate(any(), any(), any(), any(), any(), any()) } returns
                IntentionGeneratedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    intention = "Read the file.",
                    toolName = "FILES__ReadFile",
                )
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context =
                        AgentAdvancedContext(
                            chatClient = mockChatClient,
                            tools = listOf(tool),
                            instructions = null,
                            agentId = agentId,
                            confirmationManager = mockk(relaxed = true),
                        ),
                    intentionGenerator = generator,
                    objectMapper = ObjectMapper(),
                    maxIterations = 10,
                    llmProvider = "anthropic",
                    llmModel = "claude-3",
                )

            val events = agent.run(listOf(userMessage())).toList()

            events.filterIsInstance<ErrorEvent>().single().message shouldContain "context window of model claude-3"
            events.filterIsInstance<AgentFinishedEvent>().single().contextWindowExceeded shouldBe true
            io.mockk.verify(exactly = 1) { generator.generate(any(), any(), any(), any(), any(), any()) }
        }
    })
