package io.whozoss.agentos.plugins.file.tools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

class IOUtilsTest : StringSpec({

    "should execute block and return result" {
        val result = runIOWithTimeout(5) { "hello" }

        result shouldBe "hello"
    }

    "should throw TimeoutCancellationException when block exceeds timeout" {
        shouldThrow<TimeoutCancellationException> {
            runIOWithTimeout(1) {
                Thread.sleep(5_000)
                "unreachable"
            }
        }
    }

    "should propagate exceptions from block unchanged" {
        val exception = shouldThrow<IllegalStateException> {
            runIOWithTimeout(5) {
                throw IllegalStateException("boom")
            }
        }

        exception.message shouldContain "boom"
    }

    "should execute block on IO dispatcher" {
        val threadName = runIOWithTimeout(5) {
            Thread.currentThread().name
        }

        threadName shouldContain "DefaultDispatcher"
    }

    "should handle multiple concurrent calls without interference" {
        val callCount = 4
        val barrier = CyclicBarrier(callCount)
        val results = AtomicInteger(0)
        val latch = CountDownLatch(callCount)

        val threads = (1..callCount).map { i ->
            Thread {
                val result = runIOWithTimeout(10) {
                    barrier.await() // All threads start simultaneously
                    i * 10
                }
                results.addAndGet(result)
                latch.countDown()
            }
        }

        threads.forEach { it.start() }
        latch.await()

        // 10 + 20 + 30 + 40 = 100
        results.get() shouldBe 100
    }
})
