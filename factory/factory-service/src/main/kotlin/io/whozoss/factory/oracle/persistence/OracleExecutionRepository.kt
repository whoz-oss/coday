package io.whozoss.factory.oracle.persistence

import io.whozoss.factory.oracle.domain.OracleExecution
import io.whozoss.factory.oracle.domain.OracleExecutionKey
import io.whozoss.factory.oracle.domain.OracleExecutionStatus
import io.whozoss.factory.persistence.ScopedRepository
import io.whozoss.factory.persistence.TenantScope

/**
 * Tenant-scoped persistence port for the `oracle_executions` aggregate.
 *
 * Every operation takes the [TenantScope] it must run in; the scope is never
 * read from client input and never defaulted for a remote caller. The composite
 * [OracleExecutionKey] is `(namespaceId, workflowId, executionId)`.
 */
interface OracleExecutionRepository : ScopedRepository<OracleExecution, OracleExecutionKey> {

    /** Insert a brand-new execution (revision 1) and return the persisted row. */
    fun save(scope: TenantScope, execution: OracleExecution): OracleExecution

    /** Look up one execution by its composite key within the tenant scope. */
    fun findByKey(scope: TenantScope, key: OracleExecutionKey): OracleExecution?

    /**
     * Find the first execution recorded with [idempotencyKey] in the tenant
     * scope, or `null`. The key is stored in the JSONB `payload`
     * (`payload->>'idempotencyKey'`), so idempotency needs no schema change.
     */
    fun findByIdempotencyKey(scope: TenantScope, idempotencyKey: String): OracleExecution?

    /**
     * Compare-and-swap the execution status: the row is updated only when the
     * stored `revision` equals [expectedRevision]; on success `revision` is
     * incremented by one.
     *
     * @throws io.whozoss.factory.error.RevisionConflictException when the row
     *   exists but its revision differs from [expectedRevision];
     * @throws io.whozoss.factory.error.ResourceNotFoundException when no row
     *   matches the key in the tenant scope.
     */
    fun updateStatus(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        executionId: String,
        status: OracleExecutionStatus,
        expectedRevision: Int,
        artifactId: String? = null,
        evidenceId: String? = null,
        payload: String? = null,
    ): OracleExecution
}
