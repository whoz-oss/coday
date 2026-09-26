package io.whozoss.factory.workunit.service

import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workunit.domain.InvalidWorkUnitException
import io.whozoss.factory.workunit.domain.InvalidWorkUnitTransitionException
import io.whozoss.factory.workunit.domain.WorkUnit
import io.whozoss.factory.workunit.domain.WorkUnitNotFoundException
import io.whozoss.factory.workunit.domain.WorkUnitState
import io.whozoss.factory.workunit.persistence.WorkUnitRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Command to create a work unit. */
data class CreateWorkUnitCommand(
    val workUnitId: String,
    val unitType: String,
    val priority: Int = 0,
    val notBefore: Instant? = null,
    val payload: String = "{}",
)

/**
 * Domain service of the WORK UNITS aggregate.
 *
 * Owns the durable aggregate and enforces the pure state machine
 * ([WorkUnitState.canTransitionTo]) before every mutation. Port of the state
 * machine rules in `factory/src/domain/work-unit.ts`; the SQL adapter applies
 * the same vocabulary.
 */
@Service
class WorkUnitService(
    private val repository: WorkUnitRepository,
) {

    @Transactional
    fun create(scope: TenantScope, command: CreateWorkUnitCommand, now: Instant = Instant.now()): WorkUnit {
        if (command.workUnitId.isBlank() || command.unitType.isBlank()) {
            throw InvalidWorkUnitException("'workUnitId' and 'unitType' are required")
        }
        val workUnit = WorkUnit(
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            workUnitId = command.workUnitId,
            unitType = command.unitType,
            status = WorkUnitState.CREATED,
            revision = 1,
            priority = command.priority,
            notBefore = command.notBefore,
            attemptCount = 0,
            payload = command.payload,
            createdAt = now,
            updatedAt = now,
        )
        return repository.insert(scope, workUnit)
    }

    @Transactional(readOnly = true)
    fun get(scope: TenantScope, workUnitId: String): WorkUnit =
        repository.findById(scope, workUnitId)
            ?: throw WorkUnitNotFoundException("Work unit '$workUnitId' not found")

    @Transactional(readOnly = true)
    fun list(scope: TenantScope, statuses: Set<WorkUnitState>? = null): List<WorkUnit> =
        repository.list(scope, statuses)

    /**
     * Transition a work unit through the lifecycle state machine.
     *
     * @throws WorkUnitNotFoundException when the unit does not exist in scope;
     * @throws InvalidWorkUnitTransitionException when `current -> next` is not allowed.
     */
    @Transactional
    fun transition(
        scope: TenantScope,
        workUnitId: String,
        next: WorkUnitState,
        now: Instant = Instant.now(),
    ): WorkUnit {
        val current = repository.findById(scope, workUnitId)
            ?: throw WorkUnitNotFoundException("Work unit '$workUnitId' not found")
        if (current.status == next) return current
        if (!current.status.canTransitionTo(next)) {
            throw InvalidWorkUnitTransitionException(
                "Illegal work unit transition '${current.status.dbValue}' -> '${next.dbValue}'",
                details = mapOf("from" to current.status.dbValue, "to" to next.dbValue),
            )
        }
        return repository.updateStatus(scope, workUnitId, next, current.revision, now)
    }
}
