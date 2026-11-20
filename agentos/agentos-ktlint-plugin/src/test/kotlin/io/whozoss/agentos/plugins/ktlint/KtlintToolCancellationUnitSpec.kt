package io.whozoss.agentos.plugins.ktlint

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.sdk.tool.ToolContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * Cancelling the coroutine of a tool call (the user stops the case) must interrupt the blocking run behind it:
 * the waiting thread is released, the worker is cancelled and no file is handled after the one in progress.
 */
class KtlintToolCancellationUnitSpec :
    StringSpec({

        val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())

        /** Hard bound: a cancellation that does not interrupt would take the 60 s of the hanging file. */
        val cancelBoundMillis = 5_000L

        /** Longer than the cancellation, shorter than the test timeout. */
        val busyMillis = 2_000L

        /** Time given to a worker that would wrongly go on with the next file, once it is done with the busy one. */
        val graceMillis = 300L

        fun CountDownLatch.awaitOpen(): Boolean = await(cancelBoundMillis, TimeUnit.MILLISECONDS)

        /** Cancels [call] and waits for its end, bounded; returns how long that took. */
        suspend fun cancelAndTimeIt(call: Deferred<*>): Long =
            measureTimeMillis {
                call.cancel()
                withTimeout(cancelBoundMillis) { call.join() }
            }

        // --- cancellation of format ---

        "cancelling format while a file hangs interrupts it, ends the call quickly and never reaches the next files" {
            withProject { projectDir ->
                listOf("A.kt", "B.kt", "C.kt").forEach { projectDir.writeFile(relativePath = it) }
                val runner = HangingRunner(hangingOn = setOf("B.kt"))
                val tool = KtlintFormatTool(bounded = BoundedKtlintRun(projectRoot = projectDir, runner = runner))
                val input = KtlintFormatTool.Input(files = listOf("A.kt", "B.kt", "C.kt"), timeoutSeconds = TIMEOUT_SECONDS)

                val elapsedMillis =
                    runBlocking {
                        val call = async(context = Dispatchers.Default) { tool.execute(input = input, context = ctx) }
                        runner.hangStarted.awaitOpen() shouldBe true

                        cancelAndTimeIt(call = call).also {
                            call.isCancelled shouldBe true
                            shouldThrow<CancellationException> { call.await() }
                        }
                    }

                elapsedMillis shouldBeLessThan cancelBoundMillis
                runner.hangInterrupted.awaitOpen() shouldBe true
                runner.processed shouldContain "B.kt"
                runner.processed shouldNotContain "C.kt"
            }
        }

        "cancelling format while the engine is busy on a file lets that file finish but stops before the next one" {
            withProject { projectDir ->
                listOf("A.kt", "B.kt", "C.kt").forEach { projectDir.writeFile(relativePath = it) }
                val runner = BusyRunner(busyOn = setOf("B.kt"), busyMillis = busyMillis)
                val tool = KtlintFormatTool(bounded = BoundedKtlintRun(projectRoot = projectDir, runner = runner))
                val input = KtlintFormatTool.Input(files = listOf("A.kt", "B.kt", "C.kt"), timeoutSeconds = TIMEOUT_SECONDS)

                runBlocking {
                    val call = async(context = Dispatchers.Default) { tool.execute(input = input, context = ctx) }
                    runner.busyStarted.awaitOpen() shouldBe true

                    cancelAndTimeIt(call = call)

                    // the caller is released while the engine is still on the file in progress
                    runner.busyFinished.count shouldBe 1L
                    call.isCancelled shouldBe true
                }

                // the file in progress is finished, then the worker sees the interruption before the next file
                runner.busyFinished.awaitOpen() shouldBe true
                Thread.sleep(graceMillis)
                runner.processed shouldBe listOf("A.kt", "B.kt")
            }
        }

        // --- cancellation of check ---

        "cancelling check while a file hangs interrupts it and ends the call quickly" {
            withProject { projectDir ->
                listOf("A.kt", "B.kt", "C.kt").forEach { projectDir.writeFile(relativePath = it) }
                val runner = HangingRunner(hangingOn = setOf("B.kt"))
                val tool = KtlintCheckTool(bounded = BoundedKtlintRun(projectRoot = projectDir, runner = runner))
                val input = KtlintCheckTool.Input(files = listOf("A.kt", "B.kt", "C.kt"), timeoutSeconds = TIMEOUT_SECONDS)

                val elapsedMillis =
                    runBlocking {
                        val call = async(context = Dispatchers.Default) { tool.execute(input = input, context = ctx) }
                        runner.hangStarted.awaitOpen() shouldBe true

                        cancelAndTimeIt(call = call).also {
                            call.isCancelled shouldBe true
                            shouldThrow<CancellationException> { call.await() }
                        }
                    }

                elapsedMillis shouldBeLessThan cancelBoundMillis
                runner.hangInterrupted.awaitOpen() shouldBe true
                runner.processed shouldNotContain "C.kt"
            }
        }

        "cancelling check while the engine is busy on a file stops before the next one" {
            withProject { projectDir ->
                listOf("A.kt", "B.kt", "C.kt").forEach { projectDir.writeFile(relativePath = it) }
                val runner = BusyRunner(busyOn = setOf("B.kt"), busyMillis = busyMillis)
                val tool = KtlintCheckTool(bounded = BoundedKtlintRun(projectRoot = projectDir, runner = runner))
                val input = KtlintCheckTool.Input(files = listOf("A.kt", "B.kt", "C.kt"), timeoutSeconds = TIMEOUT_SECONDS)

                runBlocking {
                    val call = async(context = Dispatchers.Default) { tool.execute(input = input, context = ctx) }
                    runner.busyStarted.awaitOpen() shouldBe true

                    cancelAndTimeIt(call = call)

                    runner.busyFinished.count shouldBe 1L
                    call.isCancelled shouldBe true
                }

                runner.busyFinished.awaitOpen() shouldBe true
                Thread.sleep(graceMillis)
                runner.processed shouldBe listOf("A.kt", "B.kt")
            }
        }

        // --- no cancellation: the result is returned as before ---

        "execute of format returns its result when the coroutine is not cancelled" {
            withProject { projectDir ->
                val file = projectDir.writeFile(relativePath = "Sample.kt", content = TRAILING_SPACE_SOURCE)
                val tool = KtlintFormatTool(projectRoot = projectDir)

                val result =
                    runBlocking {
                        tool.execute(
                            input = KtlintFormatTool.Input(files = listOf("Sample.kt"), timeoutSeconds = TIMEOUT_SECONDS),
                            context = ctx,
                        )
                    }

                result.success shouldBe true
                result.output shouldContain "1 file(s) modified"
                Files.readString(file) shouldBe CLEAN_SOURCE
            }
        }

        "execute of check returns its result when the coroutine is not cancelled" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "Sample.kt", content = WILDCARD_IMPORT_SOURCE)
                val tool = KtlintCheckTool(projectRoot = projectDir)

                val result =
                    runBlocking {
                        tool.execute(
                            input = KtlintCheckTool.Input(files = listOf("Sample.kt"), timeoutSeconds = TIMEOUT_SECONDS),
                            context = ctx,
                        )
                    }

                result.success shouldBe true
                result.output shouldContain "no-wildcard-imports"
            }
        }
    })
