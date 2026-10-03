package io.whozoss.factory.workunit.domain

import java.time.Instant

/**
 * Aggregate root of a schedulable work unit.
 *
 * Maps 1:1 to the tenant-scoped V6 `work_units` row extended by V7
 * (`priority`, `not_before`, `attempt_count`). Port of the `WorkUnit` interface
 * in `factory/src/domain/work-unit.ts`.
 *
 * The `payload` is kept as its raw JSON text so the persistence layer can write
 * it verbatim into the JSONB column without imposing a schema.
 */
data class WorkUnit(
    val organizationId: String,
    val workstreamId: String,
    val workUnitId: String,
    val unitType: String,
    val status: WorkUnitState = WorkUnitState.CREATED,
    val revision: Int = 1,
    val priority: Int = 0,
    val notBefore: Instant? = null,
    val attemptCount: Int = 0,
    val payload: String = "{}",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    val terminal: Boolean
        get() = status.terminal
}
