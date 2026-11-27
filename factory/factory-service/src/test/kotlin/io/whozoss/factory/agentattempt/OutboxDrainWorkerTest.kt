package io.whozoss.factory.agentattempt

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.agentattempt.service.ContinuationExecutor
import io.whozoss.factory.agentattempt.service.DrainReport
import io.whozoss.factory.agentattempt.service.OutboxDrainService
import io.whozoss.factory.agentattempt.service.OutboxDrainWorker
import io.whozoss.factory.agentattempt.service.OutboxEvent
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.settings.RunConcurrencyLimit
import io.whozoss.factory.workflow.service.SessionRunResult
import io.whozoss.factory.workflow.service.SessionRunService
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure unit tests (no Spring, no database) of [OutboxDrainWorker].
 *
 * The drain is mocked so the tests focus on the continuation contract: a
 * `result_submitted` event is mapped to a scoped [SessionRunService.runSession]
 * call, while every other event type is ignored. The first tests run the continuation
 * synchronously (a [ContinuationExecutor] that runs the task in place); the parallel-dispatch
 * tests use the real bounded pool owned by the worker.
 */
class OutboxDrainWorkerTest {

    private val drainService = mockk<OutboxDrainService>()
    private val sessionRunService = mockk<SessionRunService>()
    private val proxy = mockk<AgentOsProxyClient>()
    private val synchronousExecutor = ContinuationExecutor { it.run() }
    private val worker = OutboxDrainWorker(
        drainService, sessionRunService, proxy, ObjectMapper(),
        continuationExecutor = synchronousExecutor,
    )
    private val queued = mutableListOf<OutboxEvent>()

    private fun stubDrain(vararg events: OutboxEvent) {
        every { drainService.enqueueContinuation(any(), any(), any(), any()) } answers {
            val source = secondArg<OutboxEvent>()
            queued.add(source.copy(eventType = "session_continuation", payload = thirdArg()))
            Unit
        }
        every { drainService.dueContinuations(any(), any(), any()) } answers { queued.toList() }
        every { drainService.claimContinuation(any(), any(), any()) } answers {
            queued.firstOrNull { it.id == secondArg<String>() }?.copy(claimToken = "owner")
        }
        every { drainService.completeContinuation(any(), any(), any(), any()) } returns Unit
        every { drainService.deferContinuation(any(), any(), any(), any(), any()) } returns Unit
        every { drainService.submissionContext(any(), any(), any(), any()) } returns null
        every { drainService.releaseContinuation(any(), any(), any()) } returns Unit
        every { drainService.pendingOrganizations() } returns listOf("org-1")
        every { drainService.drainPending(any(), any(), any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            val handler = invocation.args[3] as (OutboxEvent) -> Unit
            events.forEach(handler)
            DrainReport(dispatched = events.size, failed = 0)
        }
    }

    private fun stubRunSession() {
        every { sessionRunService.runSession(any(), any(), any(), any(), any()) } returns
            SessionRunResult("ns-1", "wf-1", "completed", emptyList())
    }

    @Test
    fun `a result_submitted event triggers a scoped sequencer continuation`() {
        stubDrain(
            OutboxEvent(
                id = "evt-1",
                eventType = "result_submitted",
                payload = """{"attemptId":"attempt-1","namespaceId":"ns-1","workflowId":"wf-1","stepId":"s1"}""",
                attempts = 0,
                workstreamId = "ws-1",
            ),
        )
        stubRunSession()
        every { proxy.resolveRepoRoot("ns-1", null) } returns "/repo"

        worker.drain()

        verify(exactly = 1) {
            sessionRunService.runSession(TenantScope("org-1", "ws-1"), "ns-1", "wf-1", Path.of("/repo"), null)
        }
    }

    @Test
    fun `a non-result event never triggers a continuation`() {
        stubDrain(OutboxEvent(id = "evt-2", eventType = "other", payload = "{}", attempts = 0, workstreamId = "ws-1"))

        worker.drain()

        verify(exactly = 0) { sessionRunService.runSession(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a continuation without continuation coordinates is ignored`() {
        stubDrain(
            OutboxEvent(
                id = "evt-3",
                eventType = "result_submitted",
                payload = """{"attemptId":"attempt-1"}""",
                attempts = 0,
                workstreamId = "ws-1",
            ),
        )

        worker.drain()

        verify(exactly = 0) { sessionRunService.runSession(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `wrapped interruption retains continuation for restart and stops the drain`() {
        stubDrain(OutboxEvent("interrupted", "session_run_requested",
            """{"namespaceId":"ns-1","workflowId":"wf-1","repoRoot":"/repo"}""", 0, "ws-1"))
        every { sessionRunService.runSession(any(), any(), any(), any(), any()) } throws
            RuntimeException("HTTP interrupted", InterruptedException("shutdown"))
        try {
            worker.drain()
            org.assertj.core.api.Assertions.assertThat(Thread.currentThread().isInterrupted).isTrue()
            verify(exactly = 1) { drainService.releaseContinuation("org-1", "interrupted", "owner") }
            verify(exactly = 0) { drainService.completeContinuation(any(), any(), any(), any()) }
            verify(exactly = 0) { drainService.deferContinuation(any(), any(), any(), any(), any()) }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `a failing continuation never breaks the worker`() {
        stubDrain(
            OutboxEvent(
                id = "evt-4",
                eventType = "result_submitted",
                payload = """{"namespaceId":"ns-1","workflowId":"wf-1"}""",
                attempts = 0,
                workstreamId = "ws-1",
            ),
        )
        every { proxy.resolveRepoRoot("ns-1", null) } returns "/repo"
        every { sessionRunService.runSession(any(), any(), any(), any(), any()) } throws IllegalStateException("boom")

        worker.drain()

        verify(exactly = 1) { sessionRunService.runSession(any(), any(), any(), any(), any()) }
    }

    // ── Parallel dispatch ───────────────────────────────────────────────────────

    private fun runRequested(id: String) = OutboxEvent(
        id, "session_run_requested",
        """{"namespaceId":"ns-1","workflowId":"wf-$id","repoRoot":"/repo"}""", 0, "ws-1",
    )

    /** Makes the continuations stateful: a claimed or completed one is no longer due. */
    private fun stubStatefulQueue() {
        val claimed = ConcurrentHashMap.newKeySet<String>()
        val done = ConcurrentHashMap.newKeySet<String>()
        every { drainService.dueContinuations(any(), any(), any()) } answers {
            queued.filter { it.id !in claimed && it.id !in done }
        }
        every { drainService.claimContinuation(any(), any(), any()) } answers {
            val id = secondArg<String>()
            queued.firstOrNull { it.id == id && claimed.add(id) }?.copy(claimToken = "owner")
        }
        every { drainService.completeContinuation(any(), any(), any(), any()) } answers {
            claimed.remove(secondArg<String>())
            done.add(secondArg<String>())
            Unit
        }
        every { drainService.releaseContinuation(any(), any(), any()) } answers {
            claimed.remove(secondArg<String>())
            Unit
        }
    }

    private fun awaitIdle(pooled: OutboxDrainWorker, expectedActive: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (pooled.activeRuns() != expectedActive && System.nanoTime() < deadline) Thread.sleep(10)
        assertThat(pooled.activeRuns()).isEqualTo(expectedActive)
    }

    private fun pooledWorker(limit: RunConcurrencyLimit) =
        OutboxDrainWorker(drainService, sessionRunService, proxy, ObjectMapper(), concurrency = limit)

    @Test
    fun `drain dispatches up to the limit concurrently and returns without waiting`() {
        stubDrain(*(0..6).map { runRequested("evt-$it") }.toTypedArray())
        stubStatefulQueue()
        val release = CountDownLatch(1)
        val started = CountDownLatch(5)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        every { sessionRunService.runSession(any(), any(), any(), any(), any()) } answers {
            peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            started.countDown()
            release.await(30, TimeUnit.SECONDS)
            running.decrementAndGet()
            SessionRunResult("ns-1", "wf-1", "completed", emptyList())
        }
        val pooled = pooledWorker { 5 }
        try {
            // Before the change drain() blocked on the first runSession and never returned here.
            CompletableFuture.runAsync { pooled.drain() }.get(10, TimeUnit.SECONDS)

            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue()
            assertThat(peak.get()).isEqualTo(5)
            assertThat(pooled.activeRuns()).isEqualTo(5)
            // The two extra continuations stay unclaimed (pending).
            verify(exactly = 5) { drainService.claimContinuation(any(), any(), any()) }
        } finally {
            release.countDown()
            pooled.shutdown()
        }
    }

    @Test
    fun `a freed slot is filled at the next tick`() {
        stubDrain(*(0..6).map { runRequested("evt-$it") }.toTypedArray())
        stubStatefulQueue()
        val permits = Semaphore(0)
        val started = AtomicInteger()
        every { sessionRunService.runSession(any(), any(), any(), any(), any()) } answers {
            started.incrementAndGet()
            permits.acquire()
            SessionRunResult("ns-1", "wf-1", "completed", emptyList())
        }
        val pooled = pooledWorker { 5 }
        try {
            pooled.drain()
            awaitIdle(pooled, 5)
            // Nothing is free: a second tick claims nothing more.
            pooled.drain()
            verify(exactly = 5) { drainService.claimContinuation(any(), any(), any()) }

            permits.release()
            awaitIdle(pooled, 4)
            pooled.drain()

            verify(exactly = 6) { drainService.claimContinuation(any(), any(), any()) }
            awaitIdle(pooled, 5)
        } finally {
            permits.release(10)
            pooled.shutdown()
        }
    }

    @Test
    fun `lowering the limit never interrupts in-flight runs and stops new dispatch`() {
        stubDrain(*(0..2).map { runRequested("evt-$it") }.toTypedArray())
        stubStatefulQueue()
        val limit = AtomicInteger(2)
        val release = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        every { sessionRunService.runSession(any(), any(), any(), any(), any()) } answers {
            try {
                release.await(30, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                interrupted.set(true)
            }
            SessionRunResult("ns-1", "wf-1", "completed", emptyList())
        }
        val pooled = pooledWorker { limit.get() }
        try {
            pooled.drain()
            awaitIdle(pooled, 2)
            limit.set(1)

            pooled.drain()

            verify(exactly = 2) { drainService.claimContinuation(any(), any(), any()) }
            assertThat(interrupted.get()).isFalse()
            assertThat(pooled.activeRuns()).isEqualTo(2)
        } finally {
            release.countDown()
            pooled.shutdown()
        }
    }

    @Test
    fun `a continuation already in flight is not claimed again`() {
        // The default stub keeps returning the same due candidate, as an expired lease would.
        stubDrain(runRequested("evt-long"))
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        every { sessionRunService.runSession(any(), any(), any(), any(), any()) } answers {
            started.countDown()
            release.await(30, TimeUnit.SECONDS)
            SessionRunResult("ns-1", "wf-1", "completed", emptyList())
        }
        val pooled = pooledWorker { 5 }
        try {
            pooled.drain()
            awaitIdle(pooled, 1)
            // The in-flight slot is taken before the pool thread calls runSession: wait for the call itself.
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue()

            pooled.drain()

            verify(exactly = 1) { drainService.claimContinuation(any(), any(), any()) }
            verify(exactly = 1) { sessionRunService.runSession(any(), any(), any(), any(), any()) }
        } finally {
            release.countDown()
            pooled.shutdown()
        }
    }

    @Test
    fun `a rejected submission releases the claim`() {
        stubDrain(runRequested("evt-rejected"))
        val rejecting = OutboxDrainWorker(
            drainService, sessionRunService, proxy, ObjectMapper(),
            continuationExecutor = ContinuationExecutor { throw RejectedExecutionException("full") },
        )

        rejecting.drain()

        verify(exactly = 1) { drainService.releaseContinuation("org-1", "evt-rejected", "owner") }
        verify(exactly = 0) { drainService.completeContinuation(any(), any(), any(), any()) }
        verify(exactly = 0) { drainService.deferContinuation(any(), any(), any(), any(), any()) }
        assertThat(rejecting.activeRuns()).isZero()
    }

    @Test
    fun `shutdown interrupts in-flight runs and releases their claims`() {
        stubDrain(runRequested("evt-a"), runRequested("evt-b"))
        stubStatefulQueue()
        val never = CountDownLatch(1)
        every { sessionRunService.runSession(any(), any(), any(), any(), any()) } answers {
            never.await(60, TimeUnit.SECONDS)
            SessionRunResult("ns-1", "wf-1", "completed", emptyList())
        }
        val pooled = pooledWorker { 5 }
        pooled.drain()
        awaitIdle(pooled, 2)

        pooled.shutdown()

        verify(exactly = 1) { drainService.releaseContinuation("org-1", "evt-a", "owner") }
        verify(exactly = 1) { drainService.releaseContinuation("org-1", "evt-b", "owner") }
        verify(exactly = 0) { drainService.completeContinuation(any(), any(), any(), any()) }
        verify(exactly = 0) { drainService.deferContinuation(any(), any(), any(), any(), any()) }
        assertThat(pooled.activeRuns()).isZero()
    }
}
