package io.whozoss.factory.oracle

import io.whozoss.factory.oracle.publisher.DefaultOracleEvidencePublisher
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.persistence.EvidenceAppendResult
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure unit tests of [DefaultOracleEvidencePublisher] — no Spring context, no
 * PostgreSQL. They pin the `oracle-result` evidence contract that
 * `WorkflowTransitionPolicy` relies on (`kind`, `outcome = pass|fail`,
 * `source.kind = factory-oracle`, `facts.oracleId`) and its deterministic replay.
 */
class DefaultOracleEvidencePublisherTest {

    private val scope = TenantScope("org-test", "ws-test")
    private val namespace = "ns-test"
    private val workflowId = "wf-test"
    private val stepId = "verify-code"
    private val oracleId = "smoke"

    /** Minimal in-memory evidence log: idempotent on the evidence id. */
    private class RecordingEvidenceRepository : WorkflowEvidenceRepository {
        val items = LinkedHashMap<String, WorkflowEvidenceItem>()

        override fun list(
            scope: TenantScope,
            namespaceId: String,
            workflowId: String,
            stepId: String?,
        ): List<WorkflowEvidenceItem> = items.values.toList()

        override fun append(
            scope: TenantScope,
            namespaceId: String,
            workflowId: String,
            item: WorkflowEvidenceItem,
        ): EvidenceAppendResult {
            items[item.evidenceId]?.let { return EvidenceAppendResult.Idempotent(it) }
            items[item.evidenceId] = item
            return EvidenceAppendResult.Created(item)
        }
    }

    private fun facts(outcome: String): Map<String, Any?> = mapOf(
        "oracleId" to oracleId,
        "oracleVersion" to "1.0.0",
        "stepId" to stepId,
        "outcome" to outcome,
    )

    @Test
    fun `publishes pass evidence shaped for the transition policy`() {
        val repository = RecordingEvidenceRepository()
        val publisher = DefaultOracleEvidencePublisher(repository)

        val evidenceId = publisher.recordEvidence(
            scope,
            namespace,
            workflowId,
            stepId,
            oracleId,
            outcome = "succeeded",
            facts = facts("succeeded"),
        )

        val item = repository.items.getValue(evidenceId)
        assertThat(item.kind).isEqualTo("oracle-result")
        assertThat(item.outcome).isEqualTo("pass")
        assertThat(item.stepId).isEqualTo(stepId)
        assertThat(item.source?.get("kind")).isEqualTo("factory-oracle")
        assertThat(item.source?.get("runtimeId")).isEqualTo("factory-dashboard")
        assertThat(item.facts["oracleId"]).isEqualTo(oracleId)
    }

    @Test
    fun `normalizes a failed execution to a fail evidence`() {
        val repository = RecordingEvidenceRepository()
        val publisher = DefaultOracleEvidencePublisher(repository)

        val evidenceId = publisher.recordEvidence(
            scope,
            namespace,
            workflowId,
            stepId,
            oracleId,
            outcome = "failed",
            facts = facts("failed"),
        )

        assertThat(repository.items.getValue(evidenceId).outcome).isEqualTo("fail")
    }

    @Test
    fun `replaying the same oracle run is idempotent and returns the same evidence id`() {
        val repository = RecordingEvidenceRepository()
        val publisher = DefaultOracleEvidencePublisher(repository)

        val first = publisher.recordEvidence(scope, namespace, workflowId, stepId, oracleId, "succeeded", facts("succeeded"))
        val replay = publisher.recordEvidence(scope, namespace, workflowId, stepId, oracleId, "succeeded", facts("succeeded"))

        assertThat(replay).isEqualTo(first)
        assertThat(repository.items).hasSize(1)
    }
}
