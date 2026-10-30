package io.whozoss.factory.oracle.publisher

import io.whozoss.factory.persistence.TenantScope

/**
 * Extension point: recording the evidence of an oracle run.
 *
 * The append-only `workflow_evidence` log is owned by the evidence aggregate, so
 * the ORACLES aggregate depends only on this port. An implementation appends one
 * immutable evidence row and returns its `evidenceId`, which
 * [io.whozoss.factory.oracle.service.OracleExecutionService] records on the
 * execution. A `null` return (or an absent implementation) simply leaves the
 * execution without an evidence link.
 */
interface OracleEvidencePublisher {

    fun recordEvidence(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        oracleId: String,
        outcome: String,
        facts: Map<String, Any?>,
    ): String?
}
