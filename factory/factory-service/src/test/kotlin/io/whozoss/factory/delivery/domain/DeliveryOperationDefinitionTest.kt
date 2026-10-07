package io.whozoss.factory.delivery.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests of the pure delivery-operation definition domain. */
class DeliveryOperationDefinitionTest {

    private val artifactDigest = "sha256:" + "a".repeat(64)
    private val targetHash = "sha256:" + "d".repeat(64)
    private val sourceCommit = "c".repeat(40)

    private fun artifactRef(): Map<String, Any?> = mapOf(
        "digest" to artifactDigest,
        "mediaType" to "application/octet-stream",
        "producerRef" to "builder-1",
        "buildRef" to "build-1",
        "sourceCommit" to sourceCommit,
    )

    private fun releaseRef(): Map<String, Any?> = mapOf(
        "releaseId" to "release-1",
        "artifactDigest" to artifactDigest,
        "sourceCommit" to sourceCommit,
        "approvedEvidenceId" to "ev-1",
    )

    private fun deploymentRequest(): Map<String, Any?> = mapOf(
        "kind" to "deployment",
        "expectedRevision" to 1,
        "idempotencyKey" to "idem-1",
        "targetId" to "prod-1",
        "artifactRef" to artifactRef(),
        "releaseRef" to releaseRef(),
    )

    @Test
    fun `deployment request normalizes with matching artifact and release`() {
        val normalized = normalizeDeliveryOperationRequest(deploymentRequest())
        assertThat(normalized).isInstanceOf(DeliveryOperationRequestNormalization.Valid::class.java)
        val value = (normalized as DeliveryOperationRequestNormalization.Valid).value
        assertThat(value.kind).isEqualTo("deployment")
        assertThat(value.artifactRef?.get("digest")).isEqualTo(artifactDigest)
        assertThat(value.releaseRef?.get("artifactDigest")).isEqualTo(artifactDigest)
    }

    @Test
    fun `unknown request field is rejected`() {
        val invalid = deploymentRequest() + mapOf("extra" to true)
        assertThat(normalizeDeliveryOperationRequest(invalid))
            .isInstanceOf(DeliveryOperationRequestNormalization.Invalid::class.java)
    }

    @Test
    fun `artifact and release identity mismatch is rejected`() {
        val mismatched = deploymentRequest() + mapOf(
            "releaseRef" to releaseRef() + mapOf("artifactDigest" to "sha256:" + "e".repeat(64)),
        )
        val result = normalizeDeliveryOperationRequest(mismatched)
        assertThat(result).isInstanceOf(DeliveryOperationRequestNormalization.Invalid::class.java)
        assertThat((result as DeliveryOperationRequestNormalization.Invalid).reason)
            .isEqualTo("artifact_identity_mismatch")
    }

    @Test
    fun `operation identity derivation is deterministic`() {
        val value = (normalizeDeliveryOperationRequest(deploymentRequest())
            as DeliveryOperationRequestNormalization.Valid).value
        val scope = DeliveryOperationScope("ns-1", "wf-1", "delivery-1", "case-1", "runtime-1")
        val first = deriveDeliveryOperationIdentity(scope, value, targetHash)
        val second = deriveDeliveryOperationIdentity(scope, value, targetHash)
        assertThat(first).isInstanceOf(DeliveryOperationIdentityDerivation.Valid::class.java)
        val one = (first as DeliveryOperationIdentityDerivation.Valid).value
        val two = (second as DeliveryOperationIdentityDerivation.Valid).value
        assertThat(one.operationId).startsWith("dop_")
        assertThat(one.operationId).isEqualTo(two.operationId)
        assertThat(one.scopeHash).isEqualTo(two.scopeHash)
        assertThat(one.semanticHash).isEqualTo(two.semanticHash)
    }

    @Test
    fun `state machine allows pending to running and rejects pending to succeeded`() {
        val previous = mapOf("operationId" to "dop_x", "state" to "pending")
        val running = mapOf("operationId" to "dop_x", "state" to "running")
        val succeeded = mapOf("operationId" to "dop_x", "state" to "succeeded")
        assertThat(validateDeliveryOperationTransition(previous, running))
            .isEqualTo(DeliveryOperationTransitionValidation.Valid)
        assertThat(validateDeliveryOperationTransition(previous, succeeded))
            .isInstanceOf(DeliveryOperationTransitionValidation.Invalid::class.java)
    }

    @Test
    fun `indeterminate transition requires a matching inspected observation`() {
        val previous = mapOf("operationId" to "dop_x", "state" to "indeterminate")
        val next = mapOf(
            "operationId" to "dop_x",
            "state" to "succeeded",
            "resolvedOperationId" to "dop_x",
        )
        assertThat(validateDeliveryOperationTransition(previous, next))
            .isInstanceOf(DeliveryOperationTransitionValidation.Invalid::class.java)
        val observation = DeliveryOperationObservation(operationId = "dop_x", state = "succeeded")
        assertThat(validateDeliveryOperationTransition(previous, next, observation))
            .isEqualTo(DeliveryOperationTransitionValidation.Valid)
    }

    @Test
    fun `operation record validates and rejects bad scope hash`() {
        val record = mapOf(
            "recordType" to "delivery-operation",
            "operationId" to "dop_x",
            "kind" to "deployment",
            "expectedRevision" to 1,
            "state" to "pending",
            "attempt" to 0,
            "requestedAt" to "2024-01-01T00:00:00.000Z",
            "scopeHash" to "sha256:" + "a".repeat(64),
            "semanticHash" to "sha256:" + "b".repeat(64),
        )
        assertThat(validateDeliveryOperationRecord(record))
            .isInstanceOf(DeliveryOperationRecordValidation.Valid::class.java)
        assertThat(validateDeliveryOperationRecord(record + mapOf("scopeHash" to "not-a-digest")))
            .isInstanceOf(DeliveryOperationRecordValidation.Invalid::class.java)
    }
}
