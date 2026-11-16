package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stopped case run reaches the agent as a coroutine cancellation. These tests pin that the agents
 * let it unwind — never reporting it as an error, never turning it into a tool result.
 */
class AgentCancellationSpec :
    StringSpec({
        timeout = 10_000

        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        fun userTurn(text: String = "do it") =
            listOf(
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor("user-1", "User", ActorRole.USER),
                    content = listOf(MessageContent.Text(text)),
                ),
            )

        fun tool(
            name: String,
            execute: suspend () -> ToolExecutionResult,
        ): StandardTool<Nothing> =
            object : StandardTool<Nothing> {
                override val name = name
                override val description = "test tool"
                override val inputSchema = "{}"
                override val version = "1.0.0"
                override val paramType: Class<Nothing>? = null

                override suspend fun execute(
                    input: Nothing?,
                    context: ToolContext,
                ): ToolExecutionResult = execute()
            }

        fun advancedAgent(
            chatClient: ChatClient,
            intentionGenerator: AgentIntentionGenerator = AgentIntentionGenerator(),
            tools: List<StandardTool<*>> = emptyList(),
        ) = AgentAdvanced(
            metadata = EntityMetadata(id = agentId),
            name = "AdvancedAgent",
            context =
                AgentAdvancedContext(
                    chatClient = chatClient,
                    tools = tools,
                    instructions = null,
                    agentId = agentId,
                    confirmationManager = mockk(relaxed = true),
                ),
            intentionGenerator = intentionGenerator,
            objectMapper = ObjectMapper(),
            maxIterations = 3,
            llmProvider = "test-provider",
            llmModel = "test-model",
        )

        // -------------------------------------------------------------------------
        // AgentAdvanced
        // -------------------------------------------------------------------------

        fun toolCallingGenerator(toolName: String): AgentIntentionGenerator {
            val generator = mockk<AgentIntentionGenerator>()
            coEvery { generator.generate(any(), any(), any(), any(), any(), any()) } returns
                IntentionGeneratedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    intention = "Call the tool.",
                    toolName = toolName,
                )
            return generator
        }

        fun parameterChatClient(): ChatClient {
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { chatClient.prompt(any<Prompt>()).call().content() } returns "<parameter>{}</parameter>"
            return chatClient
        }

        "stopping an AgentAdvanced run suspended in a tool unwinds instead of reporting a failed tool result" {
            val toolStarted = CompletableDeferred<Unit>()
            val waitingTool =
                tool("Waiting") {
                    toolStarted.complete(Unit)
                    awaitCancellation()
                }
            val emitted = CopyOnWriteArrayList<CaseEvent>()
            val agent = advancedAgent(parameterChatClient(), toolCallingGenerator("Waiting"), listOf(waitingTool))

            val run = launch { agent.run(userTurn()).collect { emitted += it } }
            toolStarted.await()
            run.cancel()

            run.join()
            run.isCancelled shouldBe true
            emitted.filterIsInstance<ToolResponseEvent>().shouldBeEmpty()
            emitted.filterIsInstance<WarnEvent>().shouldBeEmpty()
        }

        "stopping an AgentAdvanced run unwinds even when the interrupted tool fails with a wrapped exception" {
            // A blocking client interrupted by the stop rarely throws InterruptedException as is.
            // Uses real threads (Dispatchers.IO + Thread.sleep): runTest would not skip the sleep,
            // and join() without a timeout would block indefinitely if interruption fails.
            // The spec-level timeout = 10_000 is the safety net here.
            val toolStarted = CompletableDeferred<Unit>()
            val blockingTool =
                tool("Blocking") {
                    runInterruptible(Dispatchers.IO) {
                        toolStarted.complete(Unit)
                        try {
                            Thread.sleep(30_000)
                        } catch (e: InterruptedException) {
                            throw IOException("connection reset", e)
                        }
                        ToolExecutionResult.success("never")
                    }
                }
            val emitted = CopyOnWriteArrayList<CaseEvent>()
            val agent = advancedAgent(parameterChatClient(), toolCallingGenerator("Blocking"), listOf(blockingTool))

            val run = launch(Dispatchers.Default) { agent.run(userTurn()).collect { emitted += it } }
            withTimeout(2_000) { toolStarted.await() }
            run.cancel()

            withTimeout(2_000) { run.join() }
            run.isCancelled shouldBe true
            emitted.filterIsInstance<ToolResponseEvent>().shouldBeEmpty()
            emitted.filterIsInstance<WarnEvent>().shouldBeEmpty()
        }

        "a CancellationException raised by a tool while the run is still active is reported as a failed tool result" {
            // Not a stop (e.g. a timeout leaking from the tool): it must not silently end the run.
            val leakingTool = tool("Leaking") { throw CancellationException("not a stop") }
            val agent = advancedAgent(parameterChatClient(), toolCallingGenerator("Leaking"), listOf(leakingTool))

            val response = agent.run(userTurn()).filterIsInstance<ToolResponseEvent>().first()

            response.success shouldBe false
        }
    })
