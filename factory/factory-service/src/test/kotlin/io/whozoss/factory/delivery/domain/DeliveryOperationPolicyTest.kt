package io.whozoss.factory.delivery.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests of the pure delivery-operation policy. */
class DeliveryOperationPolicyTest {

    private val headCommit = "c".repeat(40)
    private val targetHash = "sha256:" + "d".repeat(64)
    private val artifactDigest = "sha256:" + "a".repeat(64)

    private fun target(supportsRollback: Boolean = true): DeliveryOperationPolicyTarget = DeliveryOperationPolicyTarget(
        targetHash = targetHash,
        supportsRollback = supportsRollback,
        verificationSuiteId = "suite-1",
        verificationSuiteHash = "sha256:" + "f".repeat(64),
    )

    private fun deploymentRequest(): NormalizedDeliveryOperationRequest = NormalizedDeliveryOperationRequest(
        kind = "deployment",
        expectedRevision = 1,
        idempotencyKey = "idem-1",
        targetId = "prod-1",
        artifactRef = mapOf("digest" to artifactDigest, "sourceCommit" to headCommit),
        releaseRef = mapOf("artifactDigest" to artifactDigest, "sourceCommit" to headCommit),
    )

    @Test
    fun `deployment is allowed from release-approved with matching commit`() {
        val decision = evaluateDeliveryOperationPolicy(
            DeliveryOperationPolicyEvaluation(
                request = deploymentRequest(),
                snapshot = DeliveryOperationPolicySnapshot(1, headCommit, "release-approved"),
                target = target(),
                identityTargetHash = targetHash,
            ),
        )
        assertThat(decision).isEqualTo(DeliveryOperationPolicyDecision.Allowed)
    }

    @Test
    fun `deployment is denied when the stage is not release-approved`() {
        val decision = evaluateDeliveryOperationPolicy(
            DeliveryOperationPolicyEvaluation(
                request = deploymentRequest(),
                snapshot = DeliveryOperationPolicySnapshot(1, headCommit, "artifact-ready"),
                target = target(),
                identityTargetHash = targetHash,
            ),
        )
        assertThat((decision as DeliveryOperationPolicyDecision.Denied).code).isEqualTo("RELEASE_NOT_APPROVED")
    }

    @Test
    fun `deployment is denied when the artifact is not at head`() {
        val request = deploymentRequest().copy(
            artifactRef = mapOf("digest" to artifactDigest, "sourceCommit" to "e".repeat(40)),
        )
        val decision = evaluateDeliveryOperationPolicy(
            DeliveryOperationPolicyEvaluation(
                request = request,
                snapshot = DeliveryOperationPolicySnapshot(1, headCommit, "release-approved"),
                target = target(),
                identityTargetHash = targetHash,
            ),
        )
        assertThat((decision as DeliveryOperationPolicyDecision.Denied).code).isEqualTo("SOURCE_COMMIT_MISMATCH")
    }

    @Test
    fun `stale revision is denied`() {
        val decision = evaluateDeliveryOperationPolicy(
            DeliveryOperationPolicyEvaluation(
                request = deploymentRequest().copy(expectedRevision = 2),
                snapshot = DeliveryOperationPolicySnapshot(1, headCommit, "release-approved"),
                target = target(),
                identityTargetHash = targetHash,
            ),
        )
        assertThat((decision as DeliveryOperationPolicyDecision.Denied).code).isEqualTo("REVISION_CONFLICT")
    }

    @Test
    fun `unresolved indeterminate operation blocks progress`() {
        val decision = evaluateDeliveryOperationPolicy(
            DeliveryOperationPolicyEvaluation(
                request = deploymentRequest(),
                snapshot = DeliveryOperationPolicySnapshot(1, headCommit, "release-approved"),
                target = target(),
                identityTargetHash = targetHash,
                existingOperations = listOf(DeliveryOperationPolicyExistingOperation("indeterminate")),
            ),
        )
        assertThat((decision as DeliveryOperationPolicyDecision.Denied).code)
            .isEqualTo("DELIVERY_OPERATION_INDETERMINATE")
    }
}
