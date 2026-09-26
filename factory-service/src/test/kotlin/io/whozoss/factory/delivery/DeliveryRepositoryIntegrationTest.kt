package io.whozoss.factory.delivery

import io.whozoss.factory.DomainIntegrationTest
import io.whozoss.factory.delivery.domain.DeliveryEvidenceItem
import io.whozoss.factory.delivery.domain.DeliveryExecutionContext
import io.whozoss.factory.delivery.domain.DeliveryPromotionDecision
import io.whozoss.factory.delivery.domain.DeliveryPromotionEvaluation
import io.whozoss.factory.delivery.domain.DeliveryPromotionRequest
import io.whozoss.factory.delivery.domain.HashedDeliveryDefinition
import io.whozoss.factory.delivery.domain.defaultDeliveryDefinition
import io.whozoss.factory.delivery.domain.evaluateDeliveryPromotion
import io.whozoss.factory.delivery.domain.hashDeliveryDefinition
import io.whozoss.factory.delivery.persistence.DeliveryRepository
import io.whozoss.factory.delivery.persistence.DeliveryStorePromoteInput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Integration tests of the SQL delivery repository against a real PostgreSQL
 * instance: snapshot persistence, append-only journal, optimistic locking,
 * dot-notation snapshot updates and idempotency handling.
 */
class DeliveryRepositoryIntegrationTest : DomainIntegrationTest() {

    @Autowired
    private lateinit var repository: DeliveryRepository

    private val namespaceId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val caseId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val environmentId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    private val workflowId = "wf-delivery-1"
    private val deliveryId = "wf-delivery-1-delivery"
    private val environmentHash = "sha256:" + "a".repeat(64)
    private val baseCommit = "0".repeat(40)
    private val headCommit = "c".repeat(40)

    private fun definition(): HashedDeliveryDefinition =
        defaultDeliveryDefinition().let { HashedDeliveryDefinition(it, hashDeliveryDefinition(it)) }

    private fun snapshot(revision: Int = 1): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to "1",
        "namespaceId" to namespaceId,
        "deliveryId" to deliveryId,
        "workflowId" to workflowId,
        "environmentId" to environmentId,
        "environmentHash" to environmentHash,
        "parentCaseId" to caseId,
        "runtimeId" to "factory-dashboard",
        "baseCommit" to baseCommit,
        "headCommit" to headCommit,
        "stage" to "implementation-ready",
        "revision" to revision,
        "definitionHash" to definition().definitionHash,
        "evidenceIds" to emptyList<String>(),
        "createdAt" to "2024-01-01T00:00:00.000Z",
        "updatedAt" to "2024-01-01T00:00:00.000Z",
    )

    private fun evidence(id: String, kind: String, outcome: String): DeliveryEvidenceItem = DeliveryEvidenceItem(
        evidenceId = id,
        namespaceId = namespaceId,
        workflowId = workflowId,
        deliveryId = deliveryId,
        environmentHash = environmentHash,
        caseId = caseId,
        headCommit = headCommit,
        kind = kind,
        outcome = outcome,
        sourceKind = "factory-build",
    )

    private fun execution(): DeliveryExecutionContext =
        DeliveryExecutionContext("factory-control-plane", namespaceId, workflowId, caseId, "factory-dashboard", "op")

    private fun promoteInput(
        expectedRevision: Int,
        requestedStage: String,
        evidenceIds: List<String>,
        idempotencyKey: String,
    ): DeliveryStorePromoteInput = DeliveryStorePromoteInput(
        namespaceId = namespaceId,
        request = DeliveryPromotionRequest(
            requestId = "req-$idempotencyKey",
            deliveryId = deliveryId,
            expectedRevision = expectedRevision,
            requestedStage = requestedStage,
            evidenceIds = evidenceIds,
            idempotencyKey = idempotencyKey,
        ),
        definition = definition(),
        evidence = listOf(
            evidence("ev-artifact", "artifact", "pass"),
            evidence("ev-oracle", "oracle-result", "pass"),
        ),
        execution = execution(),
    )

    private fun journalCount(): Int = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM delivery_journal WHERE organization_id = ? AND namespace_id = ? AND delivery_id = ?",
        Int::class.java,
        ORGANIZATION_ID,
        namespaceId,
        deliveryId,
    ) ?: 0

    @Test
    fun `create persists the snapshot and appends a journal`() {
        val created = repository.create(scope, snapshot())
        assertThat(created.ok).isTrue()
        assertThat(created.changed).isTrue()

        val read = repository.read(scope, namespaceId, deliveryId)
        assertThat(read).isNotNull
        assertThat(read!!["stage"]).isEqualTo("implementation-ready")
        assertThat(read["revision"]).isEqualTo(1)
        assertThat(journalCount()).isEqualTo(3)
    }

    @Test
    fun `identical create replay is idempotent`() {
        repository.create(scope, snapshot())
        val replay = repository.create(scope, snapshot())
        assertThat(replay.ok).isTrue()
        assertThat(replay.changed).isFalse()
        assertThat(replay.idempotent).isTrue()
        assertThat(journalCount()).isEqualTo(3)
    }

    @Test
    fun `conflicting create replay is rejected`() {
        repository.create(scope, snapshot())
        val conflict = repository.create(scope, snapshot() + mapOf("stage" to "artifact-ready"))
        assertThat(conflict.ok).isFalse()
        assertThat(conflict.error?.code).isEqualTo("DELIVERY_IDENTITY_CONFLICT")
    }

    @Test
    fun `promotion advances the stage and revision and is idempotent`() {
        repository.create(scope, snapshot())
        val promoted = repository.promote(scope, promoteInput(1, "artifact-ready", listOf("ev-artifact", "ev-oracle"), "idem-1"))
        assertThat(promoted.ok).isTrue()
        assertThat(promoted.changed).isTrue()
        val read = repository.read(scope, namespaceId, deliveryId)!!
        assertThat(read["stage"]).isEqualTo("artifact-ready")
        assertThat(read["revision"]).isEqualTo(2)

        val replay = repository.promote(scope, promoteInput(1, "artifact-ready", listOf("ev-artifact", "ev-oracle"), "idem-1"))
        assertThat(replay.ok).isTrue()
        assertThat(replay.changed).isFalse()
        assertThat(replay.idempotent).isTrue()
    }

    @Test
    fun `promotion with a reused idempotency key and different content collides`() {
        repository.create(scope, snapshot())
        repository.promote(scope, promoteInput(1, "artifact-ready", listOf("ev-artifact", "ev-oracle"), "idem-1"))
        val collision = repository.promote(
            scope,
            promoteInput(2, "release-approved", listOf("ev-artifact"), "idem-1"),
        )
        assertThat(collision.ok).isFalse()
        assertThat(collision.error?.code).isEqualTo("IDEMPOTENCY_KEY_COLLISION")
    }

    @Test
    fun `promotion with a stale revision is rejected`() {
        repository.create(scope, snapshot())
        val stale = repository.promote(scope, promoteInput(5, "artifact-ready", listOf("ev-artifact", "ev-oracle"), "idem-2"))
        assertThat(stale.ok).isFalse()
        assertThat(stale.error?.code).isEqualTo("REVISION_CONFLICT")
    }

    @Test
    fun `updateSnapshot applies dot-notation patches and appends a journal`() {
        repository.create(scope, snapshot())
        val updated = repository.updateSnapshot(
            scope,
            namespaceId,
            deliveryId,
            mapOf("headCommit" to "d".repeat(40), "git.checkpoint" to mapOf("commit" to "d".repeat(40), "changed" to true)),
            mapOf("kind" to "git-checkpoint", "idempotencyKey" to "checkpoint-1"),
        )
        assertThat(updated.ok).isTrue()
        val read = repository.read(scope, namespaceId, deliveryId)!!
        assertThat(read["headCommit"]).isEqualTo("d".repeat(40))
        @Suppress("UNCHECKED_CAST")
        val git = read["git"] as Map<String, Any?>
        assertThat(git["checkpoint"]).isNotNull
        assertThat(journalCount()).isEqualTo(6)
    }

    @Test
    fun `promotion policy agrees with the persisted snapshot`() {
        repository.create(scope, snapshot())
        val decision = evaluateDeliveryPromotion(
            DeliveryPromotionEvaluation(
                request = DeliveryPromotionRequest(
                    "req",
                    deliveryId,
                    1,
                    "artifact-ready",
                    listOf("ev-artifact", "ev-oracle"),
                    "idem-3",
                ),
                snapshot = io.whozoss.factory.delivery.domain.DeliveryPromotionSnapshot(
                    1,
                    namespaceId,
                    workflowId,
                    caseId,
                    definition().definitionHash,
                    "implementation-ready",
                    deliveryId,
                    environmentHash,
                    headCommit,
                ),
                definition = definition(),
                evidence = listOf(evidence("ev-artifact", "artifact", "pass"), evidence("ev-oracle", "oracle-result", "pass")),
                execution = execution(),
            ),
        )
        assertThat(decision).isEqualTo(DeliveryPromotionDecision.Allowed)
    }
}
