package io.whozoss.factory.oracle.persistence

import io.whozoss.factory.oracle.domain.OracleExecution
import io.whozoss.factory.oracle.domain.OracleExecutionKey
import io.whozoss.factory.oracle.domain.OracleExecutionStatus
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of the [OracleExecution] aggregate.
 *
 * The `oracle_executions` PostgreSQL row is replaced by an `:OracleExecution`
 * node whose [id] is the composite business key
 * `(organizationId, workstreamId, namespaceId, workflowId, executionId)`
 * encoded as a single string (see [compositeId]). Every scalar column of the
 * former table survives as a node property; `payload` and `idempotencyKey` stay
 * separate so idempotent replay is an indexed property lookup instead of a JSONB
 * scan.
 */
@Node("OracleExecution")
data class OracleExecutionNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
    val executionId: String,
    val oracleId: String,
    val status: String,
    val revision: Int,
    val evidenceId: String? = null,
    val artifactId: String? = null,
    val payload: String = "{}",
    val idempotencyKey: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    fun toDomain(): OracleExecution =
        OracleExecution(
            organizationId = organizationId,
            workstreamId = workstreamId,
            namespaceId = namespaceId,
            workflowId = workflowId,
            executionId = executionId,
            oracleId = oracleId,
            status = OracleExecutionStatus.fromDbValue(status),
            revision = revision,
            evidenceId = evidenceId,
            artifactId = artifactId,
            payload = payload,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    companion object {
        /** Encodes the composite [OracleExecutionKey] into a single node id. */
        fun compositeId(
            organizationId: String,
            workstreamId: String,
            key: OracleExecutionKey,
        ): String = "$organizationId|$workstreamId|${key.namespaceId}|${key.workflowId}|${key.executionId}"

        fun fromDomain(execution: OracleExecution, idempotencyKey: String?): OracleExecutionNode =
            OracleExecutionNode(
                id = compositeId(execution.organizationId, execution.workstreamId, execution.key),
                organizationId = execution.organizationId,
                workstreamId = execution.workstreamId,
                namespaceId = execution.namespaceId,
                workflowId = execution.workflowId,
                executionId = execution.executionId,
                oracleId = execution.oracleId,
                status = execution.status.dbValue,
                revision = execution.revision,
                evidenceId = execution.evidenceId,
                artifactId = execution.artifactId,
                payload = execution.payload,
                idempotencyKey = idempotencyKey,
                createdAt = execution.createdAt,
                updatedAt = execution.updatedAt,
            )
    }
}
