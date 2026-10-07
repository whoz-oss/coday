package io.whozoss.factory.oracle.publisher

import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.persistence.EvidenceAppendResult
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Default [OracleEvidencePublisher]: appends the immutable `oracle-result`
 * evidence row of a run to the workflow evidence log.
 *
 * The absence of an implementation is legal (the ORACLES aggregate depends only
 * on the port): the service then records the execution without an evidence link.
 * When the workflow aggregate is wired, this component is the single producer of
 * `oracle-result` evidence, so a step transition evaluated by
 * [io.whozoss.factory.workflow.domain.WorkflowTransitionPolicy] can find the
 * matching pass/fail fact.
 *
 * The evidence is emitted with:
 *  - `kind = "oracle-result"`;
 *  - `outcome = "pass" | "fail"` (the canonical evidence vocabulary shared by
 *    `human-decision`, `code-verification` and `agent-turn`, NOT the raw
 *    `succeeded`/`failed` execution status);
 *  - `source.kind = "factory-oracle"` and `source.runtimeId = "factory-dashboard"`,
 *    the trusted producer the transition policy authorizes;
 *  - `facts.oracleId`, `facts.oracleVersion`, `facts.stepId`, `facts.outcome`.
 *
 * The evidence id is derived deterministically from
 * `(workflowId, stepId, oracleId)` and the same tuple is used as the
 * idempotency key, so replaying an oracle run appends no duplicate row and the
 * execution always links the evidence that was actually persisted.
 */
@Component
class DefaultOracleEvidencePublisher(
    private val evidenceRepository: WorkflowEvidenceRepository,
) : OracleEvidencePublisher {

    override fun recordEvidence(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        oracleId: String,
        outcome: String,
        facts: Map<String, Any?>,
    ): String {
        val idempotencyKey = "oracle-run:$workflowId:$stepId:$oracleId"
        val evidenceId = UUID.nameUUIDFromBytes(idempotencyKey.toByteArray()).toString()
        val item = WorkflowEvidenceItem(
            evidenceId = evidenceId,
            namespaceId = namespaceId,
            workflowId = workflowId,
            stepId = stepId,
            kind = "oracle-result",
            outcome = normalizeOutcome(outcome),
            source = mapOf(
                "kind" to "factory-oracle",
                "runtimeId" to "factory-dashboard",
                "oracleId" to oracleId,
            ),
            facts = facts,
            idempotencyKey = idempotencyKey,
            createdAt = null,
        )
        return when (val result = evidenceRepository.append(scope, namespaceId, workflowId, item)) {
            is EvidenceAppendResult.Created -> result.item.evidenceId
            is EvidenceAppendResult.Idempotent -> result.item.evidenceId
            is EvidenceAppendResult.Collision -> evidenceId
        }
    }

    /**
     * Maps the oracle execution status carried by the service (`succeeded`,
     * `failed`, ...) to the canonical `pass`/`fail` evidence outcome. A value
     * already expressed as `pass`/`fail` is preserved.
     */
    private fun normalizeOutcome(outcome: String): String = when (outcome.lowercase()) {
        "pass", "succeeded" -> "pass"
        else -> "fail"
    }
}
