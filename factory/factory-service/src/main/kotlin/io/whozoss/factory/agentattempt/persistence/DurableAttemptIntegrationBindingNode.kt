package io.whozoss.factory.agentattempt.persistence

import io.whozoss.factory.agentattempt.domain.DurableAttemptIntegrationBinding
import io.whozoss.factory.persistence.TenantScope
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

@Node("DurableAttemptIntegrationBinding")
data class DurableAttemptIntegrationBindingNode(
    @Id val id: String,
    val organizationId: String,
    val workstreamId: String,
    val attemptId: String,
    val serviceIdentityId: String,
    val namespaceId: String,
    val workflowId: String,
    val stepId: String,
    val briefHash: String,
    val createdAt: Instant,
) {
    fun toDomain() = DurableAttemptIntegrationBinding(
        attemptId, serviceIdentityId, namespaceId, workflowId, stepId, briefHash, createdAt,
    )

    companion object {
        fun compositeId(scope: TenantScope, attemptId: String, serviceIdentityId: String) =
            "${scope.organizationId}|${scope.workstreamId}|$attemptId|$serviceIdentityId"
    }
}
