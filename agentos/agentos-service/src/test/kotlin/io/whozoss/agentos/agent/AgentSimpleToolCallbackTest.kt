package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import kotlinx.coroutines.flow.toList
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.tool.ToolCallback
import reactor.core.publisher.Flux
import java.util.UUID

/**
 * Tests for the ToolCallback registration and tool execution in AgentSimple.
 *
 * AgentSimple uses ToolCallback stubs to advertise tool schemas to the LLM via
 * AnthropicChatOptions. internalToolExecutionEnabled=false prevents Spring AI from
 * executing the callbacks; AgentSimple owns all tool execution in its own loop.
 *
 * These tests stub ChatModel.stream() to return ChatResponse objects with AssistantMessage
 * toolCalls to simulate the LLM requesting tool execution.
 */
class AgentSimpleToolCallbackTest :
    StringSpec({
        timeout = 5000

        fun makeAgent(
            agentId: UUID,
            chatModel: ChatModel,
            tools: Collection<StandardTool<*>>,
        ): AgentSimple {
            val model =
                AiModel(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    description = "Test agent",
                    modelName = "claude-opus",
                    providerName = "anthropic",
                )
            return AgentSimple(
                metadata = EntityMetadata(id = agentId),
                model = model,
                chatModel = chatModel,
                tools = tools,
            )
        }

        fun userMessage(
            namespaceId: UUID,
            caseId: UUID,
            text: String,
        ) = MessageEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            actor = Actor("user-1", "user", ActorRole.USER),
            content = listOf(MessageContent.Text(text)),
        )

        // -------------------------------------------------------------------------
        // Schema registration
        // -------------------------------------------------------------------------

        "toolCallbackStubs should expose the tool inputSchema verbatim in AnthropicChatOptions" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val expectedSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "timezone": {
                            "type": "string",
                            "description": "IANA timezone identifier"
                        }
                    },
                    "required": ["timezone"]
                }
                """.trimIndent()

            val fakeTool =
                object : StandardTool<Nothing> {
                    override val name = "GetCurrentDateTime"
                    override val description = "Get the current date and time in a timezone"
                    override val inputSchema = expectedSchema
                    override val version = "1.0.0"
                    override val paramType: Class<Nothing>? = null

                    override fun execute(input: Nothing?): String = ""
                }

            // Capture the Prompt to inspect the options
            val capturedPrompt = slot<Prompt>()
            val mockChatModel = mockk<ChatModel>(relaxed = true)
            every { mockChatModel.stream(capture(capturedPrompt)) } returns
                Flux.just(ChatResponse(listOf(Generation(AssistantMessage("done")))))

            val agent = makeAgent(agentId, mockChatModel, listOf(fakeTool))
            agent.run(listOf(userMessage(namespaceId, caseId, "test"))).toList()

            // The prompt options must carry the tool definitions
            val options = capturedPrompt.captured.options as? AnthropicChatOptions
            options shouldNotBe null
            val registeredCallback =
                options!!.toolCallbacks.firstOrNull { it.toolDefinition.name() == "GetCurrentDateTime" }
            registeredCallback shouldNotBe null
            registeredCallback!!.toolDefinition.inputSchema() shouldBe expectedSchema
            registeredCallback.toolDefinition.description() shouldContain "timezone"
        }

        // -------------------------------------------------------------------------
        // Tool execution via AgentSimple's own loop
        // -------------------------------------------------------------------------

        "tool calls in ChatResponse are executed by AgentSimple and recorded as events" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val receivedArgs = mutableListOf<String?>()
            val fakeTool =
                object : StandardTool<Nothing> {
                    override val name = "GetCurrentDateTime"
                    override val description = "Get the current date and time"
                    override val inputSchema =
                        """{"type":"object","properties":{"timezone":{"type":"string"}},"required":["timezone"]}"""
                    override val version = "1.0.0"
                    override val paramType: Class<Nothing>? = null

                    override fun execute(input: Nothing?): String = ""

                    override fun executeWithJson(json: String?): String {
                        receivedArgs += json
                        return """{"success":true,"datetime":"2026-02-27T11:02:37-05:00","timezone":"America/New_York"}"""
                    }
                }

            val mockChatModel = mockk<ChatModel>(relaxed = true)
            val toolCallArgs = """{"timezone":"America/New_York"}"""
            val toolCall = AssistantMessage.ToolCall("tc-1", "function", "GetCurrentDateTime", toolCallArgs)
            val toolCallResponse = ChatResponse(listOf(Generation(AssistantMessage.builder().toolCalls(listOf(toolCall)).build())))
            val finalResponse = ChatResponse(listOf(Generation(AssistantMessage("It is 11:02 AM EST"))))

            var callCount = 0
            every { mockChatModel.stream(any<Prompt>()) } answers {
                when (callCount++) {
                    0 -> Flux.just(toolCallResponse)   // first turn: LLM requests tool
                    else -> Flux.just(finalResponse)   // second turn: LLM gives final answer
                }
            }

            val agent = makeAgent(agentId, mockChatModel, listOf(fakeTool))
            val events = agent.run(listOf(userMessage(namespaceId, caseId, "what time is it in New York?"))).toList()

            // Tool received the correct args
            receivedArgs.all { it?.contains("America/New_York") == true } shouldBe true

            // ToolRequestEvent recorded the exact args
            val toolRequest = events.filterIsInstance<ToolRequestEvent>().firstOrNull()
            toolRequest shouldNotBe null
            toolRequest!!.args shouldContain "America/New_York"

            // ToolResponseEvent recorded the result
            val toolResponse = events.filterIsInstance<ToolResponseEvent>().firstOrNull()
            toolResponse shouldNotBe null
            (toolResponse!!.output as MessageContent.Text).content shouldContain "America/New_York"
        }

        // -------------------------------------------------------------------------
        // History replay safety (empty-args crash fix)
        // -------------------------------------------------------------------------

        "convertEventsToMessages should not crash when history contains a ToolRequestEvent with blank args" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val mockChatModel = mockk<ChatModel>(relaxed = true)
            stubChatModelStream(mockChatModel, "follow-up response")

            val agent = makeAgent(agentId, mockChatModel, emptyList())

            val historyWithEmptyArgs =
                listOf(
                    userMessage(namespaceId, caseId, "what time is it in New York?"),
                    io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "prior-request-id",
                        toolName = "GetCurrentDateTime",
                        args = "",
                    ),
                    io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "prior-request-id",
                        toolName = "GetCurrentDateTime",
                        output = MessageContent.Text("{\"success\":true,\"datetime\":\"2026-02-27T16:00:00Z\"}"),
                        success = true,
                    ),
                    MessageEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        actor = Actor(agentId.toString(), "TestAgent", ActorRole.AGENT),
                        content = listOf(MessageContent.Text("It is 11:00 AM EST")),
                    ),
                    userMessage(namespaceId, caseId, "and what about Paris?"),
                )

            val events = agent.run(historyWithEmptyArgs).toList()

            events shouldHaveAtLeastSize 3
            events.filterIsInstance<AgentFinishedEvent>().firstOrNull() shouldNotBe null
        }

        "convertEventsToMessages should not crash when history contains a ToolRequestEvent with null args" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val mockChatModel = mockk<ChatModel>(relaxed = true)
            stubChatModelStream(mockChatModel, "response")

            val agent = makeAgent(agentId, mockChatModel, emptyList())

            val historyWithNullArgs =
                listOf(
                    userMessage(namespaceId, caseId, "what time is it?"),
                    io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "prior-request-id",
                        toolName = "GetCurrentDateTime",
                        args = null,
                    ),
                    io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "prior-request-id",
                        toolName = "GetCurrentDateTime",
                        output = MessageContent.Text("{\"success\":true}"),
                        success = true,
                    ),
                    MessageEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        actor = Actor(agentId.toString(), "TestAgent", ActorRole.AGENT),
                        content = listOf(MessageContent.Text("It is 4 PM")),
                    ),
                    userMessage(namespaceId, caseId, "follow-up"),
                )

            val events = agent.run(historyWithNullArgs).toList()

            events shouldHaveAtLeastSize 3
            events.filterIsInstance<AgentFinishedEvent>().firstOrNull() shouldNotBe null
        }
    })
