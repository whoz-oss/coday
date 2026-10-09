package io.whozoss.factory.agentattempt.persistence

import io.whozoss.factory.agentattempt.domain.AgentStepResultCapability
import io.whozoss.factory.persistence.TenantScope
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of an issued AGENT-STEP submission capability.
 *
 * Replaces the V6 `result_capabilities` PostgreSQL row. Unlike the former JSONB
 * layout, the `sha256:<hex>` [tokenHash] is denormalised onto the node as an
 * indexed property so resolving a bearer token is an indexed equality lookup
 * instead of a tenant-wide JSONB scan (the adapter still re-verifies the digest
 * in constant time). The clear bearer token is never persisted.
 *
 * The node [id] is the composite business key
 * `(organizationId, workstreamId, capabilityId)` encoded as a single string.
 */
@Node("ResultCapability")
data class ResultCapabilityNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
    val stepId: String,
    val attemptId: String,
    val resultId: String,
    val capabilityId: String,
    val capabilityType: String,
    val tokenHash: String,
    val payload: String = "{}",
    val createdAt: Instant = Instant.now(),
) {
    companion object {
        fun compositeId(organizationId: String, workstreamId: String, capabilityId: String): String =
            "$organizationId|$workstreamId|$capabilityId"

        fun fromDomain(
            scope: TenantScope,
            resultId: String,
            capabilityType: String,
            capability: AgentStepResultCapability,
            payload: String,
            now: Instant,
        ): ResultCapabilityNode =
            ResultCapabilityNode(
                id = compositeId(scope.organizationId, scope.workstreamId, capability.capabilityId),
                organizationId = scope.organizationId,
                workstreamId = scope.workstreamId,
                namespaceId = capability.namespaceId,
                workflowId = capability.workflowId,
                stepId = capability.stepId,
                attemptId = capability.attemptId,
                resultId = resultId,
                capabilityId = capability.capabilityId,
                capabilityType = capabilityType,
                tokenHash = capability.tokenHash,
                payload = payload,
                createdAt = now,
            )
    }
}
