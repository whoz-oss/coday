package io.whozoss.agentos.sdk.tool

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Tests for [BlockingTool]: the blocking body is run interruptibly, its answer and its failures reach the caller. */
class BlockingToolSpec :
    StringSpec({
        val context =
            ToolContext(
                namespaceId = UUID.randomUUID(),
                userId = null,
                userExternalId = null,
                caseEvents = emptyList(),
            )

        "execute returns the result of the blocking body" {
            val tool = BodyTool { input, _ -> ToolExecutionResult.success("got $input") }

            val result = tool.execute("abc", context)

            result shouldBe ToolExecutionResult.success("got abc")
        }

        "an error result of the blocking body is returned as is" {
            val tool = BodyTool { _, _ -> ToolExecutionResult.error("nope", errorType = "BOOM") }

            val result = tool.execute(null, context)

            result.success shouldBe false
            result.errorType shouldBe "BOOM"
        }

        "an exception of the blocking body reaches the caller" {
            val tool = BodyTool { _, _ -> error("broken") }

            shouldThrow<IllegalStateException> { tool.execute(null, context) }.message shouldBe "broken"
        }

        "cancelling the call interrupts the blocking body" {
            val started = CountDownLatch(1)
            val interrupted = CompletableDeferred<Boolean>()
            val tool =
                BodyTool { _, _ ->
                    started.countDown()
                    try {
                        // Never reaches its end: the test cancels the call.
                        Thread.sleep(60_000)
                        interrupted.complete(false)
                    } catch (e: InterruptedException) {
                        interrupted.complete(true)
                    }
                    ToolExecutionResult.success("finished")
                }

            coroutineScope {
                val call = launch(Dispatchers.Default) { tool.execute(null, context) }
                withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) } shouldBe true
                call.cancel()
                withTimeout(10_000) { interrupted.await() } shouldBe true
            }
        }
    })

/** A [BlockingTool] whose body is given by the test. The input is a plain string. */
private class BodyTool(
    private val body: (String?, ToolContext) -> ToolExecutionResult,
) : BlockingTool<String>() {
    override val name = "BodyTool"
    override val description = "Test tool"
    override val version = "1.0.0"
    override val inputSchema = "{}"
    override val paramType: Class<String> = String::class.java

    override fun executeBlocking(
        input: String?,
        context: ToolContext,
    ): ToolExecutionResult = body(input, context)
}
