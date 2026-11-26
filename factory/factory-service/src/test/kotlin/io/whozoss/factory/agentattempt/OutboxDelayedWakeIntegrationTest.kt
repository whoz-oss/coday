package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.persistence.OutboxEventNode
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
import io.whozoss.factory.agentattempt.service.OutboxDrainService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Path
import java.time.Instant

/**
 * Real-query tests of the delayed continuation wake used by the external polling of a `waiting_external` step
 * (embedded Neo4j): a polling run must be re-woken at the requested instant, never earlier, without ever delaying a
 * genuine result wake and without disturbing an adoption backoff.
 */
class OutboxDelayedWakeIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var drain: OutboxDrainService

    @Autowired
    private lateinit var outbox: SpringDataNeo4jOutboxRepository

    private val payload = """{"namespaceId":"ns","workflowId":"wf","repoRoot":"/repo","ticket":null}"""
    private val id = OutboxDrainService.continuationId(WORKSTREAM_ID, "ns", "wf")

    @Test
    fun `a delayed wake requested while claimed becomes due only at the requested instant after acknowledgement`() {
        val now = Instant.now()
        val notBefore = now.plusSeconds(60)
        seedContinuation(now)
        val owner = drain.claimContinuation(ORGANIZATION_ID, id, now)!!

        schedule(notBefore)
        drain.completeContinuation(ORGANIZATION_ID, id, now, owner.claimToken!!)

        val node = nodeOf(id)!!
        assertThat(node.status).isEqualTo("pending")
        assertThat(node.nextAttemptAt).isEqualTo(notBefore)
        assertThat(node.wakeRequested).isNull()
        assertThat(node.wakeNotBefore).isNull()
        assertThat(drain.dueContinuations(ORGANIZATION_ID, now.plusSeconds(59))).isEmpty()
        assertThat(drain.dueContinuations(ORGANIZATION_ID, notBefore)).hasSize(1)
    }

    @Test
    fun `two delayed wakes while claimed keep the earliest`() {
        val now = Instant.now()
        seedContinuation(now)
        val owner = drain.claimContinuation(ORGANIZATION_ID, id, now)!!

        schedule(now.plusSeconds(120))
        schedule(now.plusSeconds(45))
        schedule(now.plusSeconds(90))
        drain.completeContinuation(ORGANIZATION_ID, id, now, owner.claimToken!!)

        assertThat(nodeOf(id)!!.nextAttemptAt).isEqualTo(now.plusSeconds(45))
    }

    @Test
    fun `a dispatched continuation is re-pended for the requested instant`() {
        val now = Instant.now()
        seedContinuation(now)
        val owner = drain.claimContinuation(ORGANIZATION_ID, id, now)!!
        drain.completeContinuation(ORGANIZATION_ID, id, now, owner.claimToken!!)
        assertThat(statusOf(id)).isEqualTo("dispatched")

        val notBefore = now.plusSeconds(60)
        schedule(notBefore)

        val node = nodeOf(id)!!
        assertThat(node.status).isEqualTo("pending")
        assertThat(node.nextAttemptAt).isEqualTo(notBefore)
        assertThat(node.attempts).isZero()
        assertThat(node.diagnostic).isEqualTo("EXTERNAL_POLL_SCHEDULED")
        assertThat(drain.dueContinuations(ORGANIZATION_ID, now.plusSeconds(30))).isEmpty()
        assertThat(drain.dueContinuations(ORGANIZATION_ID, notBefore)).hasSize(1)
    }

    @Test
    fun `a pending continuation keeps the earliest due time and an immediate one stays immediate`() {
        val now = Instant.now()
        seedContinuation(now)
        // Immediately due (nextAttemptAt == null): a delayed wake must never push it back.
        schedule(now.plusSeconds(60))
        assertThat(nodeOf(id)!!.nextAttemptAt).isNull()
        assertThat(drain.dueContinuations(ORGANIZATION_ID, now)).hasSize(1)

        // A later due time is lowered, an earlier one is kept.
        val claimed = drain.claimContinuation(ORGANIZATION_ID, id, now)!!
        schedule(now.plusSeconds(300))
        drain.completeContinuation(ORGANIZATION_ID, id, now, claimed.claimToken!!)
        assertThat(nodeOf(id)!!.nextAttemptAt).isEqualTo(now.plusSeconds(300))
        schedule(now.plusSeconds(60))
        assertThat(nodeOf(id)!!.nextAttemptAt).isEqualTo(now.plusSeconds(60))
        schedule(now.plusSeconds(200))
        assertThat(nodeOf(id)!!.nextAttemptAt).isEqualTo(now.plusSeconds(60))
    }

    @Test
    fun `a result wake always wins over a delayed wake in either order`() {
        val now = Instant.now()
        // Delayed first, result second.
        seedContinuation(now)
        var owner = drain.claimContinuation(ORGANIZATION_ID, id, now)!!
        schedule(now.plusSeconds(60))
        resultWake("result-1", now)
        drain.completeContinuation(ORGANIZATION_ID, id, now, owner.claimToken!!)
        assertThat(nodeOf(id)!!.status).isEqualTo("pending")
        assertThat(nodeOf(id)!!.nextAttemptAt).isNull()
        assertThat(drain.dueContinuations(ORGANIZATION_ID, now)).hasSize(1)

        // Result first, delayed second.
        owner = drain.claimContinuation(ORGANIZATION_ID, id, now)!!
        resultWake("result-2", now)
        schedule(now.plusSeconds(60))
        drain.completeContinuation(ORGANIZATION_ID, id, now, owner.claimToken!!)
        assertThat(nodeOf(id)!!.nextAttemptAt).isNull()
        assertThat(drain.dueContinuations(ORGANIZATION_ID, now)).hasSize(1)
    }

    @Test
    fun `a result wake makes a pending delayed continuation immediately due`() {
        val now = Instant.now()
        seedContinuation(now)
        val owner = drain.claimContinuation(ORGANIZATION_ID, id, now)!!
        drain.completeContinuation(ORGANIZATION_ID, id, now, owner.claimToken!!)
        schedule(now.plusSeconds(60))
        assertThat(drain.dueContinuations(ORGANIZATION_ID, now)).isEmpty()

        resultWake("result-3", now)

        assertThat(nodeOf(id)!!.nextAttemptAt).isNull()
        assertThat(drain.dueContinuations(ORGANIZATION_ID, now)).hasSize(1)
    }

    @Test
    fun `an adoption backoff is never touched by a delayed wake`() {
        val now = Instant.now()
        seedContinuation(now)
        val owner = drain.claimContinuation(ORGANIZATION_ID, id, now)!!
        drain.deferContinuation(ORGANIZATION_ID, id, now, true, owner.claimToken!!)
        val before = nodeOf(id)!!
        assertThat(before.retryDeadline).isNotNull()

        schedule(before.nextAttemptAt!!.minusSeconds(3))

        val after = nodeOf(id)!!
        assertThat(after.nextAttemptAt).isEqualTo(before.nextAttemptAt)
        assertThat(after.retryDeadline).isEqualTo(before.retryDeadline)
        assertThat(after.diagnostic).isEqualTo(before.diagnostic)
    }

    private fun schedule(notBefore: Instant) =
        drain.scheduleWake(scope, "ns", "wf", Path.of("/repo"), null, notBefore)

    /** A distinct result event handed off to the continuation, exactly as the drain worker does. */
    private fun resultWake(source: String, now: Instant) {
        insertEvent(source, now)
        drain.drainPending(ORGANIZATION_ID, now = now) { drain.enqueueContinuation(ORGANIZATION_ID, it, payload, now) }
    }

    private fun seedContinuation(now: Instant) = resultWake("seed", now)

    private fun insertEvent(eventId: String, createdAt: Instant) {
        outbox.save(
            OutboxEventNode(
                id = OutboxEventNode.compositeId(ORGANIZATION_ID, eventId),
                organizationId = ORGANIZATION_ID,
                eventId = eventId,
                workstreamId = WORKSTREAM_ID,
                eventType = "result_submitted",
                payload = payload,
                status = "pending",
                attempts = 0,
                createdAt = createdAt,
            ),
        )
    }

    private fun nodeOf(id: String): OutboxEventNode? =
        outbox.findById(OutboxEventNode.compositeId(ORGANIZATION_ID, id)).orElse(null)

    private fun statusOf(id: String): String? = nodeOf(id)?.status
}
