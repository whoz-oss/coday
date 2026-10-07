package io.whozoss.factory.oracle.domain

import java.time.Instant

/**
 * Lifecycle of one oracle run.
 *
 * The database values are the lowercase names accepted by the V6
 * `oracle_executions_status_check` constraint: `running`, `succeeded`, `failed`,
 * `cancelled`.
 */
enum class OracleExecutionStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    /** The value persisted in the `status` column. */
    val dbValue: String
        get() = name.lowercase()

    companion object {
        /** Parse a persisted status, case-insensitively. */
        fun fromDbValue(value: String): OracleExecutionStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown oracle execution status: $value")
    }
}

/** Composite identity of an oracle execution: `(namespaceId, workflowId, executionId)`. */
data class OracleExecutionKey(
    val namespaceId: String,
    val workflowId: String,
    val executionId: String,
)

/**
 * Aggregate root of one oracle run — mutable, optimistic-locked by [revision].
 *
 * Maps 1:1 to the tenant-scoped `oracle_executions` table (V6). The whole row is
 * scoped by `(organizationId, workstreamId)`; the primary key additionally
 * carries `(namespaceId, workflowId, executionId)`.
 */
data class OracleExecution(
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
    val executionId: String,
    val oracleId: String,
    val status: OracleExecutionStatus = OracleExecutionStatus.RUNNING,
    val revision: Int = 1,
    val evidenceId: String? = null,
    val artifactId: String? = null,
    val payload: String = "{}",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    val key: OracleExecutionKey
        get() = OracleExecutionKey(namespaceId, workflowId, executionId)
}
