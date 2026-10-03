package io.whozoss.factory.agentattempt.persistence

import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.persistence.TenantScope
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Neo4j implementation of [AgentStepAttemptRepository].
 *
 * Replaces `JdbcAgentStepAttemptRepository`. The attempt is the mutable root of
 * the AGENT-STEP aggregate; the result and its capability are attached to the
 * same identity so they commit in a single Neo4j transaction. Every operation is
 * constrained to the supplied [TenantScope] `(organizationId, workstreamId)`
 * because the node id is the composite business key.
 */
@Repository
class Neo4jAgentStepAttemptRepository(
    private val attempts: SpringDataNeo4jAgentStepAttemptRepository,
) : AgentStepAttemptRepository {

    @Transactional
    override fun insert(scope: TenantScope, attempt: AgentStepAttemptRecord) {
        attempts.save(AgentStepAttemptNode.fromDomain(scope, attempt))
    }

    override fun exists(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): Boolean = scoped(scope, namespaceId, workflowId, stepId, attemptId) != null

    override fun find(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): AgentStepAttemptRecord? = scoped(scope, namespaceId, workflowId, stepId, attemptId)?.toDomain()

    @Transactional
    override fun terminalize(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
        status: String,
    ): Int {
        val id = AgentStepAttemptNode.compositeId(
            scope.organizationId,
            scope.workstreamId,
            namespaceId,
            workflowId,
            stepId,
            attemptId,
        )
        return attempts.terminalize(id, status, Instant.now()).toInt()
    }

    private fun scoped(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): AgentStepAttemptNode? =
        attempts
            .findById(
                AgentStepAttemptNode.compositeId(
                    scope.organizationId,
                    scope.workstreamId,
                    namespaceId,
                    workflowId,
                    stepId,
                    attemptId,
                ),
            ).orElse(null)
            ?.takeIf { it.organizationId == scope.organizationId && it.workstreamId == scope.workstreamId }
}
