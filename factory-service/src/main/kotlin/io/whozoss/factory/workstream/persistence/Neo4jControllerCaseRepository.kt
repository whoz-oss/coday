package io.whozoss.factory.workstream.persistence

import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workstream.domain.ControllerCaseExecution
import io.whozoss.factory.workstream.domain.toDomain
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Neo4j implementation of the tenant-scoped controller case repository
 * (Phase 9).
 *
 * Every statement is constrained to the caller's [TenantScope]; the
 * organization always comes from [scope], never from caller input. The two
 * write paths are the only ones that ever create or archive controller cases,
 * both inside a transaction:
 *
 * - [startFirst] — idempotent `MERGE` of the first controller case of a
 *   workstream (a replayed start with the same `caseId` creates nothing),
 * - [archiveAndStart] — explicit compaction: archive the current active case
 *   (stamping the compaction reason) and bind a fresh active case with the
 *   next [ControllerCaseExecution.sequence], preserving the stable
 *   `controllerAgentRef` and the workstream identity.
 */
@Repository
class Neo4jControllerCaseRepository(
    private val repository: SpringDataNeo4jControllerCaseRepository,
) {

    /** The active controller case of [workstreamId] in [scope], or `null` when none started yet. */
    fun findActive(scope: TenantScope, workstreamId: String): ControllerCaseExecution? =
        repository
            .findActiveByWorkstream(scope.organizationId, workstreamId)
            ?.toDomain()

    /** Every controller case execution of [workstreamId] (active + archived), ordered by sequence. */
    fun listHistory(scope: TenantScope, workstreamId: String): List<ControllerCaseExecution> =
        repository
            .findAllByWorkstream(scope.organizationId, workstreamId)
            .map { it.toDomain() }

    /**
     * Start the first controller case of a workstream, idempotently: the
     * `MERGE` keys on the composite id, so re-starting with the same
     * `caseId` returns the pre-existing execution and writes nothing. The
     * [ControllerCaseExecution.sequence] is derived here (`max + 1`, 1 when
     * none), never trusted from the input.
     */
    @Transactional
    fun startFirst(scope: TenantScope, execution: ControllerCaseExecution): ControllerCaseExecution {
        val now = Instant.now()
        val sequence = nextSequence(scope, execution.workstreamId)
        return repository
            .mergeStart(
                id = ControllerCaseExecutionNode.compositeId(
                    scope.organizationId,
                    execution.workstreamId,
                    execution.caseId,
                ),
                organizationId = scope.organizationId,
                workstreamId = execution.workstreamId,
                caseId = execution.caseId,
                controllerAgentRef = execution.controllerAgentRef,
                sequence = sequence,
                startedAt = execution.startedAt,
                compactionReason = execution.compactionReason,
                contextSummary = execution.contextSummary,
                contextRevision = execution.contextRevision,
                now = now,
            )
            .toDomain()
    }

    /**
     * Explicit compaction / renewal, atomically: archive the current active
     * case of [workstreamId] (stamping [compactionReason] and [now]) and bind
     * a fresh `active` case with the next sequence, the same
     * [controllerAgentRef] and the bounded resumption package ([contextSummary],
     * [contextRevision]) captured for the new case. Returns the new active
     * execution.
     */
    @Transactional
    fun archiveAndStart(
        scope: TenantScope,
        workstreamId: String,
        caseId: String,
        controllerAgentRef: String,
        compactionReason: String?,
        contextSummary: String?,
        contextRevision: String?,
        now: Instant,
    ): ControllerCaseExecution {
        repository.archiveActive(scope.organizationId, workstreamId, now, compactionReason)
        val sequence = nextSequence(scope, workstreamId)
        return repository
            .mergeStart(
                id = ControllerCaseExecutionNode.compositeId(scope.organizationId, workstreamId, caseId),
                organizationId = scope.organizationId,
                workstreamId = workstreamId,
                caseId = caseId,
                controllerAgentRef = controllerAgentRef,
                sequence = sequence,
                startedAt = now,
                compactionReason = null,
                contextSummary = contextSummary,
                contextRevision = contextRevision,
                now = now,
            )
            .toDomain()
    }

    private fun nextSequence(scope: TenantScope, workstreamId: String): Int =
        ((repository.maxSequence(scope.organizationId, workstreamId) ?: 0L) + 1L).toInt()
}
