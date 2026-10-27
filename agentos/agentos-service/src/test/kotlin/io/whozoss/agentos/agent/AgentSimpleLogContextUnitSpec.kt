package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.whozoss.agentos.logging.LogContext
import io.whozoss.agentos.logging.withLogContext
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.flow.toList
import org.slf4j.MDC
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.tool.ToolCallback
import reactor.core.publisher.Flux
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Spring AI invokes tool callbacks on its own (Reactor) threads, where the MDC of the agent
 * coroutine is absent. AgentSimple must re-apply the log context captured at the start of the run,
 * both in the callback and in the coroutine that executes the tool.
 */
class AgentSimpleLogContextUnitSpec :
    StringSpec({
        timeout = 5000

        "a tool invoked from a foreign thread sees the log context of the agent run" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val seenByTool = AtomicReference<Pair<String?, String?>>()
            val seenAfterCallback = AtomicReference<String?>("unset")

            val tool =
                object : StandardTool<Nothing> {
                    override val name = "Probe"
                    override val description = "Records the MDC"
                    override val inputSchema = "{}"
                    override val version = "1.0.0"
                    override val paramType: Class<Nothing>? = null

                    override suspend fun execute(
                        input: Nothing?,
                        context: ToolContext,
                    ): ToolExecutionResult {
                        seenByTool.set(MDC.get(LogContext.CASE_ID) to MDC.get(LogContext.AGENT_NAME))
                        return ToolExecutionResult.success("ok")
                    }
                }

            val chatClient = mockk<ChatClient>(relaxed = true)
            val streamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            val callbacks = slot<List<ToolCallback>>()
            every { chatClient.prompt(any<Prompt>()).toolCallbacks(capture(callbacks)).stream() } answers {
                // Simulate Spring AI: the callback runs on a thread with an empty MDC.
                val worker =
                    Thread {
                        MDC.clear()
                        callbacks.captured.single().call("{}")
                        seenAfterCallback.set(MDC.get(LogContext.CASE_ID))
                    }
                worker.start()
                worker.join()
                streamSpec
            }
            every { streamSpec.content() } returns Flux.just("done")

            val agent =
                AgentSimple(
                    name = "dev",
                    chatClient = chatClient,
                    tools = listOf(tool),
                    llmProvider = "test-provider",
                    llmModel = "test-model",
                )
            val input =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor("user-1", "User", ActorRole.USER),
                    content = listOf(MessageContent.Text("go")),
                )

            withLogContext(LogContext.CASE_ID to caseId, LogContext.AGENT_NAME to "dev") {
                agent.run(listOf(input)).toList()
            }

            seenByTool.get() shouldBe (caseId.toString() to "dev")
            // The foreign thread's MDC is restored once the callback returns.
            seenAfterCallback.get() shouldBe null
        }
    })
