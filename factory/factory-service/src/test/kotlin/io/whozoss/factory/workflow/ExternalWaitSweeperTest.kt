package io.whozoss.factory.workflow

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.persistence.OutboxEventNode
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
import io.whozoss.factory.agentattempt.service.OutboxDrainService
import io.whozoss.factory.workflow.config.ExternalPollingProperties
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.WorkflowStepStateRecord
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.ExternalWaitSweeper
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The safety net of the parked steps against the real outbox queries: a step parked in `waiting_external` whose
 * delayed wake was lost is re-woken, a correctly armed one is left untouched.
 */
class ExternalWaitSweeperTest : Neo4jDomainIntegrationTest() {

    @Autowired private lateinit var repository: WorkflowRepository
    @Autowired private lateinit var drain: OutboxDrainService
    @Autowired private lateinit var outbox: SpringDataNeo4jOutboxRepository

    private val namespace = "sweeper-ns"
    private val now = Instant.parse("2026-06-01T10:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private fun sweeper() = ExternalWaitSweeper(repository, drain, ExternalPollingProperties(), clock)

    private fun park(workflowId: String, nextPollAt: Instant, deadlineAt: Instant = nextPollAt.plusSeconds(1500)) {
        repository.upsertStepState(
            scope,
            WorkflowStepStateRecord(
                namespace, workflowId, "ci", 1, WorkflowStatuses.WAITING_EXTERNAL,
                mapOf("poll" to mapOf("nextPollAt" to nextPollAt.toString(), "deadlineAt" to deadlineAt.toString())),
            ),
        )
    }

    private fun continuation(workflowId: String) =
        outbox.findById(OutboxEventNode.compositeId(ORGANIZATION_ID, OutboxDrainService.continuationId(WORKSTREAM_ID, namespace, workflowId)))
            .orElse(null)

    private fun seedDispatched(workflowId: String) {
        val eventId = "seed-$workflowId"
        outbox.save(
            OutboxEventNode(
                id = OutboxEventNode.compositeId(ORGANIZATION_ID, eventId), organizationId = ORGANIZATION_ID, eventId = eventId,
                workstreamId = WORKSTREAM_ID, eventType = "result_submitted",
                payload = """{"namespaceId":"$namespace","workflowId":"$workflowId","repoRoot":"/repo"}""", status = "pending", createdAt = now,
            ),
        )
        drain.drainPending(ORGANIZATION_ID, now = now) {
            drain.enqueueContinuation(ORGANIZATION_ID, it, """{"namespaceId":"$namespace","workflowId":"$workflowId","repoRoot":"/repo"}""", now)
        }
        val owner = drain.claimContinuation(ORGANIZATION_ID, OutboxDrainService.continuationId(WORKSTREAM_ID, namespace, workflowId), now)!!
        drain.completeContinuation(ORGANIZATION_ID, owner.id, now, owner.claimToken!!)
    }

    @Test
    fun `a step parked with a past due time and a dispatched continuation is re-pended and due`() {
        val id = "sweep-lost"
        seedDispatched(id)
        park(id, nextPollAt = now.minusSeconds(600))
        assertThat(continuation(id).status).isEqualTo("dispatched")

        val rearmed = sweeper().sweep()

        assertThat(rearmed).isGreaterThanOrEqualTo(1)
        assertThat(continuation(id).status).isEqualTo("pending")
        assertThat(drain.dueContinuations(ORGANIZATION_ID, Instant.now().plusSeconds(1))
            .any { it.id == OutboxDrainService.continuationId(WORKSTREAM_ID, namespace, id) }).isTrue()
    }

    @Test
    fun `a step whose continuation is absent is re-armed`() {
        val id = "sweep-absent"
        park(id, nextPollAt = now.minusSeconds(600))
        assertThat(continuation(id)).isNull()

        sweeper().sweep()

        assertThat(continuation(id).status).isEqualTo("pending")
    }

    @Test
    fun `a step whose continuation is correctly pending is left untouched`() {
        val id = "sweep-fine"
        seedDispatched(id)
        val due = now.minusSeconds(600)
        park(id, nextPollAt = due)
        drain.scheduleWake(scope, namespace, id, Path.of("/repo"), null, due)
        val before = continuation(id)

        sweeper().sweep()

        val after = continuation(id)
        assertThat(after.status).isEqualTo("pending")
        assertThat(after.nextAttemptAt).isEqualTo(before.nextAttemptAt)
        assertThat(after.diagnostic).isEqualTo(before.diagnostic)
    }

    @Test
    fun `a step that is not overdue yet is not examined`() {
        val id = "sweep-recent"
        park(id, nextPollAt = now.minusSeconds(30))

        sweeper().sweep()

        assertThat(continuation(id)).isNull()
    }

    @Test
    fun `a step past its deadline is woken so that the next run records the timeout`() {
        val id = "sweep-deadline"
        seedDispatched(id)
        park(id, nextPollAt = now.minusSeconds(2000), deadlineAt = now.minusSeconds(200))

        sweeper().sweep()

        assertThat(continuation(id).status).isEqualTo("pending")
    }
}
