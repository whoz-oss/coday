package io.whozoss.factory.agentattempt.persistence

import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AttemptClaimConflictException
import io.whozoss.factory.agentattempt.domain.AttemptLeaseFencingException
import io.whozoss.factory.agentattempt.domain.AttemptNotFoundException
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.DurableAgentAttemptJournalEntry
import io.whozoss.factory.agentattempt.domain.InvalidAttemptTransitionException
import io.whozoss.factory.error.RevisionConflictException
import io.whozoss.factory.persistence.TenantScope
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Neo4j implementation of [DurableAgentAttemptRepository].
 *
 * ## Concurrency
 * [claim] serialises competing claimants on the process-local [claimLock] and
 * runs its compare-and-set inside a `REQUIRES_NEW` transaction — the exact
 * scaffolding of
 * [io.whozoss.factory.lease.persistence.Neo4jLeaseRepository] — so a competing
 * claim never observes a snapshot taken before the winner's commit. Combined
 * with the atomic Cypher CAS, this guarantees a single execution owns the
 * attempt at any instant.
 *
 * ## Fencing
 * [transition] and [finalize] validate the state machine in-domain first (so a
 * timeout / incomplete / unknown path can never reach `succeeded`), then run
 * an owner-guarded CAS. When the CAS matches nothing, the node is read back to
 * disambiguate: a divergent `ownerToken` yields
 * [AttemptLeaseFencingException] (the lease was lost, expired or preempted),
 * an identical terminal target yields the existing record (idempotent replay),
 * anything else yields [InvalidAttemptTransitionException].
 *
 * ## Append-only transition journal
 * Every successful state change (`register` initial entry, `claim`,
 * `transition`, `finalize`, `cancel`) appends exactly one
 * [DurableAgentAttemptJournalNode] entry inside the same transaction as the
 * CAS — but only when the CAS actually matched (count > 0): a fenced or
 * conflicted no-op, and every idempotent replay, writes no journal entry. The
 * mutable attempt node stays the authoritative current state; the journal is
 * its durable transition history.
 */
@Repository
class Neo4jDurableAgentAttemptRepository(
    private val attempts: SpringDataNeo4jDurableAgentAttemptRepository,
    private val journalEntries: SpringDataNeo4jDurableAgentAttemptJournalRepository,
    transactionManager: PlatformTransactionManager,
) : DurableAgentAttemptRepository {

    private val claimTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @Transactional
    override fun register(scope: TenantScope, attempt: DurableAgentAttempt, now: Instant): DurableAgentAttempt {
        val isNew = scoped(scope, attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId) == null
        val registered = attempts.register(
            id = DurableAgentAttemptNode.compositeId(
                scope.organizationId,
                scope.workstreamId,
                attempt.namespaceId,
                attempt.workflowId,
                attempt.stepId,
                attempt.attemptId,
            ),
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            namespaceId = attempt.namespaceId,
            workflowId = attempt.workflowId,
            stepId = attempt.stepId,
            attemptId = attempt.attemptId,
            caseId = attempt.caseId,
            agentName = attempt.agentName,
            attemptNumber = attempt.attemptNumber,
            capabilityToken = attempt.capabilityToken,
            turnCorrelation = attempt.turnCorrelation,
            commandId = attempt.commandId,
            brief = attempt.brief,
            now = now,
        )
        if (isNew) {
            // Initial journal entry of the attempt: registration itself is the
            // first recorded transition (null -> pending). A re-registration is
            // an idempotent replay and appends nothing.
            appendJournal(
                scope = scope,
                namespaceId = attempt.namespaceId,
                workflowId = attempt.workflowId,
                stepId = attempt.stepId,
                attemptId = attempt.attemptId,
                fromStatus = null,
                toStatus = AgentAttemptStatus.PENDING,
                ownerToken = null,
                revisionAfter = registered.revision,
                now = now,
            )
        }
        return registered.toDomain()
    }

    override fun journal(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): List<DurableAgentAttemptJournalEntry> =
        journalEntries.findByAttempt(
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            namespaceId = namespaceId,
            workflowId = workflowId,
            stepId = stepId,
            attemptId = attemptId,
        ).map { it.toDomain() }

    override fun findNonTerminal(limit: Int): List<ScopedDurableAgentAttempt> =
        attempts.findNonTerminal(limit.toLong()).map { node ->
            ScopedDurableAgentAttempt(TenantScope(node.organizationId, node.workstreamId), node.toDomain())
        }

    override fun findByAttemptId(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        attemptId: String,
    ): DurableAgentAttempt? =
        attempts.findByAttemptId(
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            namespaceId = namespaceId,
            workflowId = workflowId,
            attemptId = attemptId,
        ).firstOrNull()?.toDomain()

    override fun findByWorkflow(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
    ): List<DurableAgentAttempt> =
        attempts.findByWorkflowId(
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            namespaceId = namespaceId,
            workflowId = workflowId,
        ).map { it.toDomain() }

    override fun find(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): DurableAgentAttempt? = scoped(scope, namespaceId, workflowId, stepId, attemptId)?.toDomain()

    override fun claim(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
        ownerToken: String,
        leaseExpiresAt: Instant?,
        now: Instant,
    ): DurableAgentAttempt {
        val id = requireId(scope, namespaceId, workflowId, stepId, attemptId)
        val claimed = claimLock.withLock {
            claimTransaction.execute {
                val before = attempts.findById(id).orElse(null)
                val matched = attempts.claim(id, ownerToken, leaseExpiresAt, now)
                if (matched > 0L && before != null) {
                    appendJournal(
                        scope = scope,
                        namespaceId = namespaceId,
                        workflowId = workflowId,
                        stepId = stepId,
                        attemptId = attemptId,
                        fromStatus = AgentAttemptStatus.fromDbValue(before.status),
                        toStatus = AgentAttemptStatus.CLAIMING,
                        ownerToken = ownerToken,
                        revisionAfter = before.revision + 1,
                        now = now,
                    )
                }
                matched
            }
        } ?: 0L
        if (claimed > 0L) {
            return requireNode(scope, namespaceId, workflowId, stepId, attemptId).toDomain()
        }
        val current = requireNode(scope, namespaceId, workflowId, stepId, attemptId).toDomain()
        throw AttemptClaimConflictException(
            "Attempt '$attemptId' is already claimed",
            details = mapOf(
                "attemptId" to attemptId,
                "workflowId" to workflowId,
                "stepId" to stepId,
                "status" to current.status.dbValue,
                "currentOwnerToken" to current.ownerToken,
                "incomingOwnerToken" to ownerToken,
            ),
        )
    }

    @Transactional
    override fun transition(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
        ownerToken: String,
        target: AgentAttemptStatus,
        lastObservedEventId: String?,
        now: Instant,
    ): DurableAgentAttempt {
        if (target.terminal) {
            throw InvalidAttemptTransitionException(
                "Terminal status '${target.dbValue}' must be reached through finalize",
                details = mapOf("attemptId" to attemptId, "targetStatus" to target.dbValue),
            )
        }
        val current = requireNode(scope, namespaceId, workflowId, stepId, attemptId).toDomain()
        if (current.status == target && current.ownerToken == ownerToken) {
            return current
        }
        assertNotFenced(current, ownerToken, target)
        assertTransitionAllowed(current, target)
        val id = requireId(scope, namespaceId, workflowId, stepId, attemptId)
        val transitioned = attempts.transition(id, ownerToken, target.dbValue, lastObservedEventId, now)
        if (transitioned > 0L) {
            val after = requireNode(scope, namespaceId, workflowId, stepId, attemptId)
            appendJournal(
                scope = scope,
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = stepId,
                attemptId = attemptId,
                fromStatus = current.status,
                toStatus = target,
                ownerToken = ownerToken,
                lastObservedEventId = lastObservedEventId ?: current.lastObservedEventId,
                revisionAfter = after.revision,
                now = now,
            )
            return after.toDomain()
        }
        return disambiguateFailedCas(scope, namespaceId, workflowId, stepId, attemptId, ownerToken, target)
    }

    @Transactional
    override fun finalize(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
        ownerToken: String,
        target: AgentAttemptStatus,
        failureCode: String?,
        resultEvidenceId: String?,
        lastObservedEventId: String?,
        now: Instant,
    ): DurableAgentAttempt {
        if (!target.terminal) {
            throw InvalidAttemptTransitionException(
                "Finalize requires a terminal status, got '${target.dbValue}'",
                details = mapOf("attemptId" to attemptId, "targetStatus" to target.dbValue),
            )
        }
        val current = requireNode(scope, namespaceId, workflowId, stepId, attemptId).toDomain()
        // Idempotent replay: already at the requested terminal status with the same owner.
        if (current.status == target && current.ownerToken == ownerToken) {
            return current
        }
        // Fencing takes precedence over the state machine: a worker whose lease
        // token diverged (lost, expired or preempted) is fenced out whatever the
        // requested target.
        assertNotFenced(current, ownerToken, target)
        // State-machine guard: this is what makes it impossible for a timeout,
        // an incomplete result or an unknown state to ever finalize as `succeeded`.
        assertTransitionAllowed(current, target)
        val id = requireId(scope, namespaceId, workflowId, stepId, attemptId)
        val finalized = attempts.finalize(
            id,
            ownerToken,
            target.dbValue,
            failureCode,
            resultEvidenceId,
            lastObservedEventId,
            now,
        )
        if (finalized > 0L) {
            val after = requireNode(scope, namespaceId, workflowId, stepId, attemptId)
            appendJournal(
                scope = scope,
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = stepId,
                attemptId = attemptId,
                fromStatus = current.status,
                toStatus = target,
                ownerToken = ownerToken,
                failureCode = failureCode,
                resultEvidenceId = resultEvidenceId,
                lastObservedEventId = lastObservedEventId ?: current.lastObservedEventId,
                revisionAfter = after.revision,
                now = now,
            )
            return after.toDomain()
        }
        return disambiguateFailedCas(scope, namespaceId, workflowId, stepId, attemptId, ownerToken, target)
    }

    @Transactional
    override fun cancel(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
        expectedRevision: Int?,
        failureCode: String?,
        now: Instant,
    ): DurableAgentAttempt {
        val current = requireNode(scope, namespaceId, workflowId, stepId, attemptId).toDomain()
        // Idempotent cancellation replay: already interrupted.
        if (current.status == AgentAttemptStatus.INTERRUPTED) return current
        if (current.status.terminal) {
            throw InvalidAttemptTransitionException(
                "Attempt '$attemptId' is already terminal as '${current.status.dbValue}' and cannot be cancelled",
                details = mapOf("attemptId" to attemptId, "status" to current.status.dbValue),
            )
        }
        val revision = expectedRevision ?: current.revision
        if (revision != current.revision) {
            throw RevisionConflictException(
                "Attempt '$attemptId' is at revision ${current.revision}, expected $revision",
                details = mapOf("attemptId" to attemptId, "currentRevision" to current.revision, "expectedRevision" to revision),
            )
        }
        val id = requireId(scope, namespaceId, workflowId, stepId, attemptId)
        val cancelled = attempts.cancel(id, revision, "cancel:$attemptId", failureCode, now)
        if (cancelled > 0L) {
            val after = requireNode(scope, namespaceId, workflowId, stepId, attemptId)
            appendJournal(
                scope = scope,
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = stepId,
                attemptId = attemptId,
                fromStatus = current.status,
                toStatus = AgentAttemptStatus.INTERRUPTED,
                ownerToken = "cancel:$attemptId",
                failureCode = failureCode,
                revisionAfter = after.revision,
                now = now,
            )
            return after.toDomain()
        }
        // The CAS matched nothing: the attempt changed (terminal or revision bump)
        // between the read and the write — surface the precise conflict.
        val after = requireNode(scope, namespaceId, workflowId, stepId, attemptId).toDomain()
        return when {
            after.status == AgentAttemptStatus.INTERRUPTED -> after
            after.status.terminal -> throw InvalidAttemptTransitionException(
                "Attempt '$attemptId' became terminal as '${after.status.dbValue}' and cannot be cancelled",
                details = mapOf("attemptId" to attemptId, "status" to after.status.dbValue),
            )
            else -> throw RevisionConflictException(
                "Attempt '$attemptId' is at revision ${after.revision}, expected $revision",
                details = mapOf("attemptId" to attemptId, "currentRevision" to after.revision, "expectedRevision" to revision),
            )
        }
    }

    /**
     * Append one entry to the attempt transition journal. Called only after a
     * state change actually landed (the CAS matched), inside the same
     * transaction, so the journal and the mutable snapshot never diverge.
     */
    private fun appendJournal(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
        fromStatus: AgentAttemptStatus?,
        toStatus: AgentAttemptStatus,
        ownerToken: String?,
        failureCode: String? = null,
        resultEvidenceId: String? = null,
        lastObservedEventId: String? = null,
        revisionAfter: Int,
        now: Instant,
    ) {
        val sequence = journalEntries.maxSequence(
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            namespaceId = namespaceId,
            workflowId = workflowId,
            stepId = stepId,
            attemptId = attemptId,
        ) + 1
        journalEntries.save(
            DurableAgentAttemptJournalNode(
                id = DurableAgentAttemptJournalNode.compositeId(
                    scope.organizationId,
                    scope.workstreamId,
                    namespaceId,
                    workflowId,
                    stepId,
                    attemptId,
                    sequence,
                ),
                organizationId = scope.organizationId,
                workstreamId = scope.workstreamId,
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = stepId,
                attemptId = attemptId,
                sequence = sequence,
                fromStatus = fromStatus?.dbValue,
                toStatus = toStatus.dbValue,
                ownerToken = ownerToken,
                failureCode = failureCode,
                resultEvidenceId = resultEvidenceId,
                lastObservedEventId = lastObservedEventId,
                revisionAfter = revisionAfter,
                recordedAt = now,
            ),
        )
    }

    /**
     * The owner-guarded CAS matched nothing: read the node back and decide.
     * A divergent owner means the caller's lease token is stale (fencing); an
     * attempt already at the requested target is an idempotent replay; anything
     * else is an invalid transition.
     */
    private fun disambiguateFailedCas(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
        ownerToken: String,
        target: AgentAttemptStatus,
    ): DurableAgentAttempt {
        val after = requireNode(scope, namespaceId, workflowId, stepId, attemptId).toDomain()
        return when {
            after.ownerToken != ownerToken -> throw AttemptLeaseFencingException(
                "Attempt '$attemptId' is owned by a different lease token",
                details = mapOf(
                    "attemptId" to attemptId,
                    "status" to after.status.dbValue,
                    "currentOwnerToken" to after.ownerToken,
                    "incomingOwnerToken" to ownerToken,
                ),
            )
            after.status == target -> after
            else -> throw InvalidAttemptTransitionException(
                "Cannot transition attempt '$attemptId' from '${after.status.dbValue}' to '${target.dbValue}'",
                details = mapOf(
                    "attemptId" to attemptId,
                    "fromStatus" to after.status.dbValue,
                    "toStatus" to target.dbValue,
                ),
            )
        }
    }

    /**
     * Fast fencing check ahead of the state-machine guard: a claimed attempt
     * whose owner diverges from [ownerToken] rejects the mutation with
     * [AttemptLeaseFencingException]. An unclaimed (`pending`) attempt has no
     * owner and falls through to the state-machine guard.
     */
    private fun assertNotFenced(current: DurableAgentAttempt, ownerToken: String, target: AgentAttemptStatus) {
        if (current.ownerToken != null && current.ownerToken != ownerToken) {
            throw AttemptLeaseFencingException(
                "Attempt '${current.attemptId}' is owned by a different lease token",
                details = mapOf(
                    "attemptId" to current.attemptId,
                    "status" to current.status.dbValue,
                    "toStatus" to target.dbValue,
                    "currentOwnerToken" to current.ownerToken,
                    "incomingOwnerToken" to ownerToken,
                ),
            )
        }
    }

    private fun assertTransitionAllowed(current: DurableAgentAttempt, target: AgentAttemptStatus) {
        if (!current.canTransitionTo(target)) {
            throw InvalidAttemptTransitionException(
                "Cannot transition attempt '${current.attemptId}' from " +
                    "'${current.status.dbValue}' to '${target.dbValue}'",
                details = mapOf(
                    "attemptId" to current.attemptId,
                    "fromStatus" to current.status.dbValue,
                    "toStatus" to target.dbValue,
                ),
            )
        }
    }

    private fun requireId(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): String {
        scoped(scope, namespaceId, workflowId, stepId, attemptId)
            ?: throw AttemptNotFoundException(
                "Durable attempt '$attemptId' not found",
                details = mapOf(
                    "namespaceId" to namespaceId,
                    "workflowId" to workflowId,
                    "stepId" to stepId,
                    "attemptId" to attemptId,
                ),
            )
        return DurableAgentAttemptNode.compositeId(
            scope.organizationId,
            scope.workstreamId,
            namespaceId,
            workflowId,
            stepId,
            attemptId,
        )
    }

    private fun requireNode(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): DurableAgentAttemptNode =
        scoped(scope, namespaceId, workflowId, stepId, attemptId)
            ?: throw AttemptNotFoundException(
                "Durable attempt '$attemptId' not found",
                details = mapOf(
                    "namespaceId" to namespaceId,
                    "workflowId" to workflowId,
                    "stepId" to stepId,
                    "attemptId" to attemptId,
                ),
            )

    private fun scoped(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): DurableAgentAttemptNode? =
        attempts
            .findById(
                DurableAgentAttemptNode.compositeId(
                    scope.organizationId,
                    scope.workstreamId,
                    namespaceId,
                    workflowId,
                    stepId,
                    attemptId,
                ),
            ).orElse(null)
            ?.takeIf { it.organizationId == scope.organizationId && it.workstreamId == scope.workstreamId }

    private companion object {
        /**
         * Serialises every claim of the embedded (single-process) engine,
         * mirroring the former `FOR UPDATE SKIP LOCKED` non-blocking claim.
         */
        private val claimLock = ReentrantLock()
    }
}
