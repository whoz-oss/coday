package io.whozoss.factory.oracle.publisher

import io.whozoss.factory.persistence.TenantScope

/**
 * Extension point: publishing the artifact produced by an oracle run.
 *
 * The ORACLES aggregate only depends on this port; the concrete implementation
 * (which flips `artifacts.availability_status` to `available`, Amendment 5
 * upload-then-commit) is contributed by the artifacts aggregate wiring, so the
 * two aggregates never couple at the class level. When an oracle terminalizes
 * with an `artifactId`, [io.whozoss.factory.oracle.service.OracleExecutionService]
 * invokes this port inside the same transaction as the status compare-and-swap.
 */
interface OracleArtifactPublisher {

    /**
     * Mark [artifactId] as available, scoped to the caller's tenant and the
     * instance it belongs to.
     */
    fun publishArtifact(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        artifactId: String,
    )
}
