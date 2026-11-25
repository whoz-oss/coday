package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.persistence.OutboxEventNode
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
import io.whozoss.factory.agentattempt.service.OutboxDrainService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

/**
 * Embedded-Neo4j integration tests of [OutboxDrainService] (the former V4
 * `outbox_events` table is now an `:OutboxEvent` node).
 *
 * Extends [Neo4jDomainIntegrationTest]: the engine is the in-process Neo4j test
 * harness, so no Docker is required.
 */
class OutboxDrainServiceIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var drain: OutboxDrainService

    @Autowired
    private lateinit var outbox: SpringDataNeo4jOutboxRepository

    @Test
    fun `drains pending events and marks them dispatched`() {
        insertEvent("evt-1", "pending", Instant.parse("2026-05-01T00:00:01Z"))
        insertEvent("evt-2", "pending", Instant.parse("2026-05-01T00:00:02Z"))

        val report = drain.drainPending(ORGANIZATION_ID, limit = 10)

        assertThat(report.dispatched).isEqualTo(2)
        assertThat(report.failed).isEqualTo(0)
        assertThat(statusOf("evt-1")).isEqualTo("dispatched")
        assertThat(statusOf("evt-2")).isEqualTo("dispatched")
        assertThat(nodeOf("evt-1")?.dispatchedAt).isNotNull()
    }

    @Test
    fun `a failing handler marks the event failed and increments attempts`() {
        insertEvent("evt-bad", "pending", Instant.parse("2026-05-01T00:00:01Z"))

        val report = drain.drainPending(ORGANIZATION_ID, limit = 10) { throw IllegalStateException("ENGINE_FAILURE") }

        assertThat(report.dispatched).isEqualTo(0)
        assertThat(report.failed).isEqualTo(1)
        assertThat(statusOf("evt-bad")).isEqualTo("failed")
        assertThat(nodeOf("evt-bad")?.attempts).isEqualTo(1)
    }

    @Test
    fun `only pending events are drained and the limit is honoured`() {
        insertEvent("pending-1", "pending", Instant.parse("2026-05-01T00:00:01Z"))
        insertEvent("dispatched-1", "dispatched", Instant.parse("2026-05-01T00:00:02Z"))
        insertEvent("pending-2", "pending", Instant.parse("2026-05-01T00:00:03Z"))

        val report = drain.drainPending(ORGANIZATION_ID, limit = 1)

        assertThat(report.dispatched).isEqualTo(1)
        assertThat(statusOf("pending-1")).isEqualTo("dispatched")
        assertThat(statusOf("pending-2")).isEqualTo("pending")
        assertThat(statusOf("dispatched-1")).isEqualTo("dispatched")
    }

    @Test
    fun `pending organizations lists only tenants with pending events`() {
        insertEvent("evt-pending", "pending", Instant.parse("2026-05-01T00:00:01Z"))

        assertThat(drain.pendingOrganizations()).containsExactly(ORGANIZATION_ID)
    }

    @Test
    fun `handoff is durable and idempotent and survives loss of the in-memory continuation`() {
        val now = Instant.now()
        insertEvent("source", "pending", now)
        fun handoff() = drain.drainPending(ORGANIZATION_ID, now = now) { event ->
            drain.enqueueContinuation(ORGANIZATION_ID, event, """{"namespaceId":"ns","workflowId":"wf","repoRoot":"/repo","ticket":"#1684"}""", now)
        }
        handoff()
        assertThat(statusOf("source")).isEqualTo("dispatched")
        assertThat(drain.dueContinuations(ORGANIZATION_ID, now)).hasSize(1)
        // Re-delivery of the source never resets the continuation or its retry budget.
        outbox.save(nodeOf("source")!!.copy(status = "pending"))
        handoff()
        assertThat(drain.dueContinuations(ORGANIZATION_ID, now)).hasSize(1)
        assertThat(drain.dueContinuations(ORGANIZATION_ID, now).single().workstreamId).isEqualTo(WORKSTREAM_ID)
    }

    @Test
    fun `adoption backoff grows past generic retry limit then expires without hotloop`() {
        var now = Instant.now()
        insertEvent("source", "pending", now)
        drain.drainPending(ORGANIZATION_ID, now = now) {
            drain.enqueueContinuation(ORGANIZATION_ID, it, "{}", now)
        }
        val id = OutboxDrainService.continuationId(WORKSTREAM_ID, "", "")
        assertThat(nodeOf(id)!!.retryDeadline).isNull()
        // Ordinary queue downtime does not consume the adoption retry budget.
        now = now.plusSeconds(7 * 24 * 3600)
        val deadline = now.plusSeconds(OutboxDrainService.RETRY_SECONDS)
        var previousDelay = 0L
        var retries = 0
        while (now.isBefore(deadline)) {
            val pending = drain.dueContinuations(ORGANIZATION_ID, now).single()
            val claimed = drain.claimContinuation(ORGANIZATION_ID, pending.id, now)!!
            drain.deferContinuation(ORGANIZATION_ID, pending.id, now, true, claimed.claimToken!!)
            retries++
            val node = nodeOf(pending.id)!!
            if (node.status == "dead") break
            val next = node.nextAttemptAt!!
            val delay = java.time.Duration.between(now, next).seconds
            assertThat(delay).isGreaterThanOrEqualTo(previousDelay).isLessThanOrEqualTo(300)
            assertThat(drain.dueContinuations(ORGANIZATION_ID, next.minusMillis(1))).isEmpty()
            previousDelay = delay
            now = next
        }
        assertThat(retries).isGreaterThan(10)
        assertThat(statusOf(id)).isEqualTo("dead")
        assertThat(nodeOf(id)!!.diagnostic).isEqualTo("WORKSPACE_ADOPTION_RETRY_EXHAUSTED")
        assertThat(drain.dueContinuations(ORGANIZATION_ID, deadline.plusSeconds(1))).isEmpty()
    }

    @Test
    fun `non adoption exceptions are not automatically replayed`() {
        val now = Instant.now()
        insertEvent("source", "pending", now)
        drain.drainPending(ORGANIZATION_ID, now = now) { drain.enqueueContinuation(ORGANIZATION_ID, it, "{}", now) }
        val id = OutboxDrainService.continuationId(WORKSTREAM_ID, "", "")
        val claimed = drain.claimContinuation(ORGANIZATION_ID, id, now)!!
        drain.deferContinuation(ORGANIZATION_ID, id, now, false, claimed.claimToken!!)
        assertThat(statusOf(id)).isEqualTo("dead")
        assertThat(drain.dueContinuations(ORGANIZATION_ID, now.plusSeconds(1000))).isEmpty()
    }

    @Test
    fun `common boundary starts deadline on first transient and does not extend or duplicate active intent`() {
        val now = Instant.now()
        insertEvent("source", "pending", now)
        drain.drainPending(ORGANIZATION_ID, now = now) {
            drain.enqueueContinuation(ORGANIZATION_ID, it,
                """{"namespaceId":"ns","workflowId":"wf","repoRoot":"/repo","ticket":"#1684"}""", now)
        }
        val id = OutboxDrainService.continuationId(WORKSTREAM_ID, "ns", "wf")
        assertThat(nodeOf(id)!!.retryDeadline).isNull()
        drain.adoptionDeferred(scope, "ns", "wf", java.nio.file.Path.of("/repo"), "#1684")
        val deferred = nodeOf(id)!!
        assertThat(deferred.retryDeadline).isNotNull()
        drain.adoptionDeferred(scope, "ns", "wf", java.nio.file.Path.of("/repo"), "#1684")
        assertThat(nodeOf(id)!!.retryDeadline).isEqualTo(deferred.retryDeadline)
        assertThat(nodeOf(id)!!.payload).isEqualTo(deferred.payload)
        assertThat(outbox.findAllByOrganization(ORGANIZATION_ID).filter { it.eventType == "session_continuation" }).hasSize(1)
    }

    @Test
    fun `racing claims have exactly one owner and source during claim retains wakeup`() {
        val now = Instant.now()
        insertEvent("source", "pending", now)
        fun handoff() = drain.drainPending(ORGANIZATION_ID, now = now) {
            drain.enqueueContinuation(ORGANIZATION_ID, it, "{}", now)
        }
        handoff()
        val id = OutboxDrainService.continuationId(WORKSTREAM_ID, "", "")
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val futures = (1..2).map { executor.submit<io.whozoss.factory.agentattempt.service.OutboxEvent?> {
                start.await()
                drain.claimContinuation(ORGANIZATION_ID, id, now)
            } }
            start.countDown()
            val owners = futures.mapNotNull { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            assertThat(owners).hasSize(1)
            insertEvent("new-result", "pending", now)
            handoff()
            drain.completeContinuation(ORGANIZATION_ID, id, now, owners.single().claimToken!!)
            assertThat(statusOf(id)).isEqualTo("pending")
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `expired lease is recovered and stale owner cannot acknowledge or defer`() {
        val now = Instant.now()
        insertEvent("source", "pending", now)
        drain.drainPending(ORGANIZATION_ID, now = now) { drain.enqueueContinuation(ORGANIZATION_ID, it, "{}", now) }
        val id = OutboxDrainService.continuationId(WORKSTREAM_ID, "", "")
        val first = drain.claimContinuation(ORGANIZATION_ID, id, now)!!
        assertThat(drain.claimContinuation(ORGANIZATION_ID, id, now)).isNull()
        assertThat(drain.dueContinuations(ORGANIZATION_ID, now)).isEmpty()
        val later = now.plusSeconds(OutboxDrainService.LEASE_SECONDS + 1)
        val recovered = drain.claimContinuation(ORGANIZATION_ID, id, later)!!
        assertThat(recovered.claimToken).isNotEqualTo(first.claimToken)
        drain.completeContinuation(ORGANIZATION_ID, id, later, first.claimToken!!)
        drain.deferContinuation(ORGANIZATION_ID, id, later, true, first.claimToken!!)
        assertThat(nodeOf(id)!!.claimToken).isEqualTo(recovered.claimToken)
        assertThat(nodeOf(id)!!.retryDeadline).isNull()
        drain.completeContinuation(ORGANIZATION_ID, id, later, recovered.claimToken!!)
        assertThat(statusOf(id)).isEqualTo("dispatched")
    }

    @Test
    fun `explicit submission after dead can enqueue again via existing submission path`() {
        val service = io.whozoss.factory.workflow.service.SessionRunSubmissionService(outbox, com.fasterxml.jackson.databind.ObjectMapper())
        val now = Instant.now()
        val first = service.submit(scope, "ns", "wf", "/repo", "run", "#1684")
        drain.drainPending(ORGANIZATION_ID, now = now) {
            drain.enqueueContinuation(ORGANIZATION_ID, it, """{"namespaceId":"ns","workflowId":"wf","repoRoot":"/repo"}""", now)
        }
        val id = OutboxDrainService.continuationId(WORKSTREAM_ID, "ns", "wf")
        val claim = drain.claimContinuation(ORGANIZATION_ID, id, now)!!
        drain.deferContinuation(ORGANIZATION_ID, id, now, false, claim.claimToken!!)
        val next = service.submit(scope, "ns", "wf", "/repo", "run", "#1684")
        assertThat(next.queued).isTrue()
        assertThat(next.submissionId).isEqualTo(first.submissionId)
        assertThat(service.submit(scope, "ns", "wf", "/repo", "run", "#1684").idempotent).isTrue()
        drain.drainPending(ORGANIZATION_ID, now = now) {
            drain.enqueueContinuation(ORGANIZATION_ID, it, """{"namespaceId":"ns","workflowId":"wf","repoRoot":"/repo"}""", now)
        }
        assertThat(nodeOf(id)!!.retryDeadline).isNull()
        assertThat(statusOf(id)).isEqualTo("pending")
    }

    @Test
    fun `result root resolution outage is outside transaction durable past ten attempts and restart`() {
        var now = Instant.now()
        insertEvent("result", "pending", now)
        outbox.save(nodeOf("result")!!.copy(payload = """{"namespaceId":"ns","workflowId":"wf"}"""))
        val proxy = io.mockk.mockk<io.whozoss.factory.proxy.AgentOsProxyClient>()
        val session = io.mockk.mockk<io.whozoss.factory.workflow.service.SessionRunService>()
        var offline = true
        io.mockk.every { proxy.resolveRepoRoot("ns", null) } answers {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            if (offline) throw io.whozoss.factory.proxy.AgentOsUnavailableException("offline", java.io.IOException("reset"))
            "/repo"
        }
        io.mockk.every { session.runSession(any(), any(), any(), any(), any()) } returns
            io.whozoss.factory.workflow.service.SessionRunResult("ns", "wf", "completed", emptyList())
        val clock = object : java.time.Clock() {
            override fun getZone() = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
            override fun instant() = now
        }
        fun run() = io.whozoss.factory.agentattempt.service.OutboxDrainWorker(drain, session, proxy,
            com.fasterxml.jackson.databind.ObjectMapper(), clock,
            continuationExecutor = io.whozoss.factory.agentattempt.service.ContinuationExecutor { it.run() }).drain()
        repeat(12) {
            run() // Fresh worker, same durable context, no source poison after ten HTTP errors.
            val pending = outbox.findAllByOrganization(ORGANIZATION_ID).single { it.eventType == "session_continuation" }
            assertThat(pending.status).isEqualTo("pending")
            assertThat(pending.payload).contains("repoRootSource", "agentos-namespace")
            now = pending.nextAttemptAt!!
        }
        assertThat(statusOf("result")).isEqualTo("dispatched")
        offline = false
        run()
        io.mockk.verify(exactly = 1) { session.runSession(scope, "ns", "wf", java.nio.file.Path.of("/repo"), null) }
        assertThat(outbox.findAllByOrganization(ORGANIZATION_ID).single { it.eventType == "session_continuation" }.status)
            .isEqualTo("dispatched")
    }

    @Test
    fun `result continuation prefers persisted submission context without contacting AgentOS`() {
        val now = Instant.now()
        outbox.save(OutboxEventNode(OutboxEventNode.compositeId(ORGANIZATION_ID, "submission"), ORGANIZATION_ID,
            "submission", WORKSTREAM_ID, "session_run_requested",
            """{"namespaceId":"ns","workflowId":"wf","repoRoot":"/original","ticket":"#1684"}""", "dispatched"))
        insertEvent("result", "pending", now)
        outbox.save(nodeOf("result")!!.copy(payload = """{"namespaceId":"ns","workflowId":"wf"}"""))
        val proxy = io.mockk.mockk<io.whozoss.factory.proxy.AgentOsProxyClient>()
        val session = io.mockk.mockk<io.whozoss.factory.workflow.service.SessionRunService>()
        io.mockk.every { session.runSession(any(), any(), any(), any(), any()) } returns
            io.whozoss.factory.workflow.service.SessionRunResult("ns", "wf", "completed", emptyList())
        io.whozoss.factory.agentattempt.service.OutboxDrainWorker(drain, session, proxy, com.fasterxml.jackson.databind.ObjectMapper(),
            continuationExecutor = io.whozoss.factory.agentattempt.service.ContinuationExecutor { it.run() }).drain()
        io.mockk.verify(exactly = 0) { proxy.resolveRepoRoot(any(), any()) }
        io.mockk.verify(exactly = 1) { session.runSession(scope, "ns", "wf", java.nio.file.Path.of("/original"), "#1684") }
    }

    @Test
    fun `fresh wake resets old cycle on ack or defer whereas duplicate source cannot reset budget`() {
        val now = Instant.now()
        listOf(false, true).forEach { defer ->
            val wf = "wf-$defer"
            val payload = """{"namespaceId":"ns","workflowId":"$wf","repoRoot":"/repo"}"""
            val source = "source-$defer"
            insertEvent(source, "pending", now)
            fun handoff() = drain.drainPending(ORGANIZATION_ID, now = now) {
                drain.enqueueContinuation(ORGANIZATION_ID, it, payload, now)
            }
            handoff()
            val id = OutboxDrainService.continuationId(WORKSTREAM_ID, "ns", wf)
            val first = drain.claimContinuation(ORGANIZATION_ID, id, now)!!
            drain.deferContinuation(ORGANIZATION_ID, id, now, true, first.claimToken!!)
            val old = nodeOf(id)!!
            val due = old.nextAttemptAt!!
            val owner = drain.claimContinuation(ORGANIZATION_ID, id, due)!!
            // Duplicate delivery of same source: no additional wake and no budget reset.
            outbox.save(nodeOf(source)!!.copy(status = "pending"))
            handoff()
            assertThat(nodeOf(id)!!.wakeRequested).isNotEqualTo(true)
            assertThat(nodeOf(id)!!.retryDeadline).isEqualTo(old.retryDeadline)
            // Distinct result/human submission is a new durable wake, not an adoption retry.
            insertEvent("fresh-$defer", "pending", now)
            handoff()
            assertThat(nodeOf(id)!!.wakeRequested).isTrue()
            if (defer) drain.deferContinuation(ORGANIZATION_ID, id, due, true, owner.claimToken!!)
            else drain.completeContinuation(ORGANIZATION_ID, id, due, owner.claimToken!!)
            val fresh = nodeOf(id)!!
            assertThat(fresh.status).isEqualTo("pending")
            assertThat(fresh.attempts).isZero()
            assertThat(fresh.retryDeadline).isNull()
            assertThat(fresh.nextAttemptAt).isNull()
            assertThat(fresh.diagnostic).isNull()
            assertThat(fresh.wakeRequested).isNull()
            assertThat(fresh.wakePayload).isNull()
            val finalOwner = drain.claimContinuation(ORGANIZATION_ID, id, due)!!
            drain.completeContinuation(ORGANIZATION_ID, id, due, finalOwner.claimToken!!)
            assertThat(statusOf(id)).isEqualTo("dispatched")
        }
    }

    @Test
    fun `fresh result during pending backoff starts new cycle near old deadline but duplicate does not`() {
        val start = Instant.parse("2026-05-01T00:00:00Z")
        val payload = """{"namespaceId":"ns","workflowId":"wf","repoRoot":"/repo","ticket":"#1684"}"""
        val id = OutboxDrainService.continuationId(WORKSTREAM_ID, "ns", "wf")
        fun handoff(now: Instant) = drain.drainPending(ORGANIZATION_ID, now = now) {
            drain.enqueueContinuation(ORGANIZATION_ID, it, payload, now)
        }
        insertEvent("source", "pending", start)
        handoff(start)
        val first = drain.claimContinuation(ORGANIZATION_ID, id, start)!!
        drain.deferContinuation(ORGANIZATION_ID, id, start, true, first.claimToken!!)
        val oldDeadline = nodeOf(id)!!.retryDeadline!!
        val nearDeadline = oldDeadline.minusSeconds(11)
        val second = drain.claimContinuation(ORGANIZATION_ID, id, nearDeadline)!!
        drain.deferContinuation(ORGANIZATION_ID, id, nearDeadline, true, second.claimToken!!)
        val old = nodeOf(id)!!
        assertThat(old.status).isEqualTo("pending")
        assertThat(old.nextAttemptAt).isEqualTo(oldDeadline.minusSeconds(1))
        val arrival = oldDeadline.minusSeconds(5)
        assertThat(drain.dueContinuations(ORGANIZATION_ID, arrival)).isEmpty()

        // Same source during backoff must preserve due time and retry budget.
        outbox.save(nodeOf("source")!!.copy(status = "pending"))
        handoff(arrival)
        assertThat(nodeOf(id)!!.attempts).isEqualTo(old.attempts)
        assertThat(nodeOf(id)!!.retryDeadline).isEqualTo(oldDeadline)
        assertThat(nodeOf(id)!!.nextAttemptAt).isEqualTo(old.nextAttemptAt)

        // A distinct result is immediately due, independent of the old cycle.
        insertEvent("new-result", "pending", arrival)
        handoff(arrival)
        val fresh = nodeOf(id)!!
        assertThat(fresh.status).isEqualTo("pending")
        assertThat(fresh.lastWakeSource).isEqualTo("new-result")
        assertThat(fresh.payload).isEqualTo(payload)
        assertThat(fresh.attempts).isZero()
        assertThat(fresh.retryDeadline).isNull()
        assertThat(fresh.nextAttemptAt).isNull()
        assertThat(fresh.diagnostic).isNull()
        assertThat(fresh.wakeRequested).isNull()
        assertThat(fresh.wakePayload).isNull()
        assertThat(drain.dueContinuations(ORGANIZATION_ID, arrival)).hasSize(1)
        val owner = drain.claimContinuation(ORGANIZATION_ID, id, arrival)!!
        drain.deferContinuation(ORGANIZATION_ID, id, arrival, true, owner.claimToken!!)
        val deferred = nodeOf(id)!!
        // The next probe crosses the OLD deadline without killing the new result.
        val due = deferred.nextAttemptAt!!
        assertThat(due).isEqualTo(oldDeadline)
        assertThat(deferred.retryDeadline).isEqualTo(arrival.plusSeconds(OutboxDrainService.RETRY_SECONDS))
        outbox.save(nodeOf("new-result")!!.copy(status = "pending"))
        handoff(arrival.plusSeconds(1))
        assertThat(nodeOf(id)!!.attempts).isEqualTo(deferred.attempts)
        assertThat(nodeOf(id)!!.retryDeadline).isEqualTo(deferred.retryDeadline)
        assertThat(nodeOf(id)!!.nextAttemptAt).isEqualTo(due)
        assertThat(nodeOf(id)!!.diagnostic).isEqualTo(deferred.diagnostic)
        val resumed = drain.claimContinuation(ORGANIZATION_ID, id, due)!!
        drain.completeContinuation(ORGANIZATION_ID, id, due, resumed.claimToken!!)
        assertThat(statusOf(id)).isEqualTo("dispatched")
    }

    @Test
    fun `duplicate source cannot revive dead cycle without explicit resubmission`() {
        val now = Instant.now()
        insertEvent("source", "pending", now)
        fun handoff() = drain.drainPending(ORGANIZATION_ID, now = now) { drain.enqueueContinuation(ORGANIZATION_ID, it, "{}", now) }
        handoff()
        val id = OutboxDrainService.continuationId(WORKSTREAM_ID, "", "")
        val owner = drain.claimContinuation(ORGANIZATION_ID, id, now)!!
        drain.deferContinuation(ORGANIZATION_ID, id, now, false, owner.claimToken!!)
        outbox.save(nodeOf("source")!!.copy(status = "pending"))
        handoff()
        assertThat(statusOf(id)).isEqualTo("dead")
    }

    @Test
    fun `intentional interruption releases claim immediately with token fencing`() {
        val now = Instant.now()
        insertEvent("source", "pending", now)
        drain.drainPending(ORGANIZATION_ID, now = now) { drain.enqueueContinuation(ORGANIZATION_ID, it, "{}", now) }
        val id = OutboxDrainService.continuationId(WORKSTREAM_ID, "", "")
        val first = drain.claimContinuation(ORGANIZATION_ID, id, now)!!
        drain.releaseContinuation(ORGANIZATION_ID, id, first.claimToken!!)
        val second = drain.claimContinuation(ORGANIZATION_ID, id, now)!!
        drain.releaseContinuation(ORGANIZATION_ID, id, first.claimToken!!)
        assertThat(nodeOf(id)!!.claimToken).isEqualTo(second.claimToken)
    }

    private fun insertEvent(id: String, status: String, createdAt: Instant) {
        outbox.save(
            OutboxEventNode(
                id = OutboxEventNode.compositeId(ORGANIZATION_ID, id),
                organizationId = ORGANIZATION_ID,
                eventId = id,
                workstreamId = WORKSTREAM_ID,
                eventType = "result_submitted",
                payload = "{}",
                status = status,
                attempts = 0,
                createdAt = createdAt,
            ),
        )
    }

    private fun nodeOf(id: String): OutboxEventNode? =
        outbox.findById(OutboxEventNode.compositeId(ORGANIZATION_ID, id)).orElse(null)

    private fun statusOf(id: String): String? = nodeOf(id)?.status
}
