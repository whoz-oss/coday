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
            val traceSeenByCallback = AtomicReference<String?>()
            val afterCallback = AtomicReference<Map<String, String>?>()

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
                        traceSeenByCallback.set(MDC.get("traceId"))
                        return ToolExecutionResult.success("ok")
                    }
                }

            val chatClient = mockk<ChatClient>(relaxed = true)
            val streamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            val callbacks = slot<List<ToolCallback>>()
            every { chatClient.prompt(any<Prompt>()).toolCallbacks(capture(callbacks)).stream() } answers {
                // Simulate Spring AI: the callback runs on a pooled thread carrying its own MDC
                // (an infrastructure trace id, and a stale caseId left by a previous task).
                val worker =
                    Thread {
                        MDC.setContextMap(mapOf("traceId" to "trace-1", LogContext.CASE_ID to "stale-case"))
                        callbacks.captured.single().call("{}")
                        afterCallback.set(MDC.getCopyOfContextMap())
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

            // The tool sees the run's context, never the stale caseId of the pooled thread.
            seenByTool.get() shouldBe (caseId.toString() to "dev")
            // Foreign keys of the callback thread are kept: the callback captured them in withMdc,
            // but the tool itself runs in a fresh runBlocking context built from the run's MDC only.
            traceSeenByCallback.get() shouldBe null
            // The pooled thread gets its own MDC back, unchanged, once the callback returns.
            afterCallback.get() shouldBe mapOf("traceId" to "trace-1", LogContext.CASE_ID to "stale-case")
        }
    })
