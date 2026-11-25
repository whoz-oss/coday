package io.whozoss.agentos.plugins.ktlint

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/** The threads ktlint runs on, and what a run does when it cannot get one in time. */
class KtlintWorkersUnitSpec :
    StringSpec({

        "the plugin runs ktlint on a small pool of named platform threads, not on virtual threads" {
            val thread = KtlintWorkers.executor.submit(Callable { Thread.currentThread() }).get(10, TimeUnit.SECONDS)

            thread.isVirtual shouldBe false
            thread.isDaemon shouldBe true
            thread.name shouldStartWith "ktlint-worker-"
            KtlintWorkers.size shouldBeInRange 1..4
        }

        "a run that gets no worker within its time box gives up without starting, and does not wait to settle" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "A.kt")
                val pool = daemonPool(threads = 1)
                val release = CountDownLatch(1)
                pool.execute { release.await() }
                val runner = BusyRunner(busyOn = emptySet(), busyMillis = 0)
                val tool = KtlintFormatTool(bounded = isolatedRun(projectRoot = projectDir, runner = runner, worker = pool))

                val elapsedMillis =
                    measureTimeMillis {
                        tool.format(files = listOf("A.kt"), timeoutSeconds = 1).errorText() shouldContain "could not start within 1 seconds"
                    }

                // far under the 5 s given to a started worker to settle: nothing was running
                elapsedMillis shouldBeLessThan 3_000L
                release.countDown()
                pool.submit(Callable { }).get(10, TimeUnit.SECONDS)
                // the abandoned run never reaches the engine, even once a worker is free
                runner.processed.shouldBeEmpty()
            }
        }

        "a run that waits for a worker starts as soon as one is free, inside its time box" {
            withProject { projectDir ->
                projectDir.writeFile(relativePath = "A.kt")
                val pool = daemonPool(threads = 1)
                pool.execute { Thread.sleep(300) }
                val tool =
                    KtlintFormatTool(
                        bounded = isolatedRun(projectRoot = projectDir, runner = FailingRunner(failingOn = emptySet()), worker = pool),
                    )

                tool.format(files = listOf("A.kt"), timeoutSeconds = TIMEOUT_SECONDS).successText() shouldContain "Formatted 1 file(s)"
            }
        }
    })
