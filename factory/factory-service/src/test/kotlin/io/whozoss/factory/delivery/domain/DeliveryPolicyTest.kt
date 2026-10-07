package io.whozoss.factory.delivery.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests of the pure delivery-promotion policy. */
class DeliveryPolicyTest {

    private val namespaceId = "11111111-1111-4111-8111-111111111111"
    private val caseId = "22222222-2222-4222-8222-222222222222"
    private val deliveryId = "wf-1-delivery"
    private val workflowId = "wf-1"
    private val environmentHash = "sha256:" + "a".repeat(64)
    private val headCommit = "b".repeat(40)

    private fun definition(): HashedDeliveryDefinition =
        defaultDeliveryDefinition().let { HashedDeliveryDefinition(it, hashDeliveryDefinition(it)) }

    private fun snapshot(stage: String, revision: Int): DeliveryPromotionSnapshot = DeliveryPromotionSnapshot(
        revision = revision,
        namespaceId = namespaceId,
        workflowId = workflowId,
        parentCaseId = caseId,
        definitionHash = definition().definitionHash,
        stage = stage,
        deliveryId = deliveryId,
        environmentHash = environmentHash,
        headCommit = headCommit,
    )

    private fun evidence(
        id: String,
        kind: String,
        outcome: String,
        sourceKind: String = "factory-build",
    ): DeliveryEvidenceItem = DeliveryEvidenceItem(
        evidenceId = id,
        namespaceId = namespaceId,
        workflowId = workflowId,
        deliveryId = deliveryId,
        environmentHash = environmentHash,
        caseId = caseId,
        headCommit = headCommit,
        kind = kind,
        outcome = outcome,
        sourceKind = sourceKind,
    )

    private fun controlPlaneExecution(): DeliveryExecutionContext =
        DeliveryExecutionContext("factory-control-plane", namespaceId, workflowId, caseId, "factory-dashboard", "op")

    @Test
    fun `ordered promotion to artifact-ready is allowed with matching evidence`() {
        val request = DeliveryPromotionRequest("req", deliveryId, 1, "artifact-ready", listOf("ev1", "ev2"), "idem-1")
        val decision = evaluateDeliveryPromotion(
            DeliveryPromotionEvaluation(
                request = request,
                snapshot = snapshot("implementation-ready", 1),
                definition = definition(),
                evidence = listOf(evidence("ev1", "artifact", "pass"), evidence("ev2", "oracle-result", "pass")),
                execution = controlPlaneExecution(),
            ),
        )
        assertThat(decision).isEqualTo(DeliveryPromotionDecision.Allowed)
    }

    @Test
    fun `stale revision is denied`() {
        val request = DeliveryPromotionRequest("req", deliveryId, 5, "artifact-ready", listOf("ev1"), "idem-1")
        val decision = evaluateDeliveryPromotion(
            DeliveryPromotionEvaluation(
                request,
                snapshot("implementation-ready", 1),
                definition(),
                listOf(evidence("ev1", "artifact", "pass")),
                controlPlaneExecution(),
            ),
        )
        assertThat((decision as DeliveryPromotionDecision.Denied).code).isEqualTo("REVISION_CONFLICT")
    }

    @Test
    fun `skipping a stage is denied`() {
        val request = DeliveryPromotionRequest("req", deliveryId, 1, "release-approved", listOf("ev1"), "idem-1")
        val decision = evaluateDeliveryPromotion(
            DeliveryPromotionEvaluation(
                request,
                snapshot("implementation-ready", 1),
                definition(),
                listOf(evidence("ev1", "human-decision", "approved", "factory-human")),
                DeliveryExecutionContext("factory-human", namespaceId, workflowId, caseId, "factory-dashboard", "actor"),
            ),
        )
        assertThat((decision as DeliveryPromotionDecision.Denied).code).isEqualTo("ILLEGAL_PROMOTION")
    }

    @Test
    fun `missing required evidence is denied`() {
        val request = DeliveryPromotionRequest("req", deliveryId, 1, "artifact-ready", listOf("ev1"), "idem-1")
        val decision = evaluateDeliveryPromotion(
            DeliveryPromotionEvaluation(
                request,
                snapshot("implementation-ready", 1),
                definition(),
                listOf(evidence("ev1", "artifact", "pass")),
                controlPlaneExecution(),
            ),
        )
        assertThat((decision as DeliveryPromotionDecision.Denied).code).isEqualTo("PASS_EVIDENCE_REQUIRED")
    }

    @Test
    fun `release approval requires a human execution context`() {
        val request = DeliveryPromotionRequest("req", deliveryId, 1, "artifact-ready", emptyList(), "idem-1")
        val decision = evaluateDeliveryPromotion(
            DeliveryPromotionEvaluation(
                request,
                snapshot("implementation-ready", 1),
                definition(),
                emptyList(),
                DeliveryExecutionContext("factory-human", namespaceId, workflowId, caseId, "factory-dashboard", "actor"),
            ),
        )
        assertThat((decision as DeliveryPromotionDecision.Denied).code).isEqualTo("ACTOR_NOT_AUTHORIZED")
    }

    @Test
    fun `promotion request validation rejects unknown fields`() {
        val result = validateDeliveryPromotionRequest(
            mapOf(
                "deliveryId" to deliveryId,
                "expectedRevision" to 1,
                "requestedStage" to "artifact-ready",
                "evidenceIds" to emptyList<String>(),
                "idempotencyKey" to "idem-1",
                "extra" to true,
            ),
            deliveryId,
        )
        assertThat(result).isInstanceOf(DeliveryPromotionRequestValidation.Invalid::class.java)
    }

    @Test
    fun `applyDeliveryPromotion increments revision and unions evidence`() {
        val request = DeliveryPromotionRequest("req", deliveryId, 1, "artifact-ready", listOf("ev2"), "idem-1")
        val next = applyDeliveryPromotion(
            mapOf("revision" to 1, "stage" to "implementation-ready", "evidenceIds" to listOf("ev1")),
            request,
            "2024-01-01T00:00:00.000Z",
        )
        assertThat(next["stage"]).isEqualTo("artifact-ready")
        assertThat(next["revision"]).isEqualTo(2)
        assertThat(next["evidenceIds"]).isEqualTo(listOf("ev1", "ev2"))
    }
}
