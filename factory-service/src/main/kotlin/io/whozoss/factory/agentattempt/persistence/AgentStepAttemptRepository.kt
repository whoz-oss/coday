package io.whozoss.factory.agentattempt.persistence

import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.persistence.TenantScope

/**
 * Persistence port of the `agent_step_attempts` aggregate root (V6).
 *
 * The attempt is the mutable root of the AGENT-STEP aggregate; the result and
 * its capability are attached to the same identity
 * `(organizationId, workstreamId, namespaceId, workflowId, stepId, attemptId)`
 * so they can be committed in a single PostgreSQL transaction.
 */
interface AgentStepAttemptRepository {

    /** Insert a fresh attempt row (`status` defaults to `running`). */
    fun insert(scope: TenantScope, attempt: AgentStepAttemptRecord)

    /** True when the attempt row exists in the caller's scope. */
    fun exists(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): Boolean

    /** Read the attempt row, or `null` when absent. */
    fun find(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): AgentStepAttemptRecord?

    /**
     * Transition the attempt to a terminal status, incrementing the
     * optimistic-locking `revision`. Returns the number of updated rows.
     */
    fun terminalize(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
        status: String,
    ): Int
}
