package io.whozoss.factory.worktree

import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyStaleRelease
import io.whozoss.factory.worktree.persistence.Neo4jWorktreeExecutionOccupancyRepository
import java.nio.file.Path
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

class WorktreeExecutionOccupancyResolutionException(message: String) : RuntimeException(message)

data class WorktreeExecutionOccupancyHandle(
    val environmentId: String,
    val resourceIdentity: String,
    val ownerScope: TenantScope,
    val ownerKind: String,
    val ownerId: String,
    val generation: Long,
)

/** Resolves one persisted local environment and acquires its physical-worktree occupation before side effects. */
@Service
class WorktreeExecutionOccupancyService(
    private val environments: WorkEnvironmentRepository,
    private val occupations: Neo4jWorktreeExecutionOccupancyRepository,
    private val identities: LocalWorktreeResourceIdentityResolver = LocalWorktreeResourceIdentityResolver(),
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)
    fun acquireForWorkflow(
        scope: TenantScope,
        workflowId: String,
        ownerKind: String,
        ownerId: String,
        expectedRepoRoot: Path? = null,
    ): WorktreeExecutionOccupancyHandle {
        val matches = environments.list(scope).filter { it.workflowId == workflowId }
        if (matches.size != 1) {
            throw WorktreeExecutionOccupancyResolutionException(
                "Expected exactly one persisted work environment for workflow '$workflowId', found ${matches.size}",
            )
        }
        return acquire(scope, matches.single(), ownerKind, ownerId, expectedRepoRoot)
    }

    fun acquireForEnvironment(
        scope: TenantScope,
        environmentId: String,
        ownerKind: String,
        ownerId: String,
        expectedRepoRoot: Path? = null,
    ): WorktreeExecutionOccupancyHandle {
        val environment = environments.findByEnvironmentId(scope, environmentId)
            ?: throw WorktreeExecutionOccupancyResolutionException("Work environment '$environmentId' was not found")
        return acquire(scope, environment, ownerKind, ownerId, expectedRepoRoot)
    }

    /**
     * Acquires the occupation for the environment captured by an agent attempt, BEFORE any remote
     * AgentOS call (case creation may already provision the worktree).
     *
     * The environment must still be bound (`ready`/`busy`) and, when [expectedRevision] is given,
     * must still be at the revision captured at attempt reservation. Anything else is refused with
     * [WorktreeExecutionOccupancyResolutionException] so the caller fails closed before side effects.
     * Re-acquiring with the same owner is idempotent and returns the current generation.
     */
    fun acquireForAttemptEnvironment(
        scope: TenantScope,
        environmentId: String,
        expectedRevision: Int,
        ownerKind: String,
        ownerId: String,
    ): WorktreeExecutionOccupancyHandle {
        val environment = environments.findByEnvironmentId(scope, environmentId)
            ?: throw WorktreeExecutionOccupancyResolutionException("Work environment '$environmentId' was not found")
        if (environment.lifecycleState != WorkEnvironmentState.READY) {
            throw WorktreeExecutionOccupancyResolutionException(
                "Work environment '$environmentId' is '${environment.lifecycleState.dbValue}', not READY",
            )
        }
        if (environment.revision != expectedRevision) {
            throw WorktreeExecutionOccupancyResolutionException(
                "Work environment '$environmentId' is at revision ${environment.revision}, expected $expectedRevision",
            )
        }
        return acquire(scope, environment, ownerKind, ownerId, null)
    }

    /**
     * Acquires and binds in one transaction. The attempt snapshot (including claim owner/revision)
     * is the fence: a concurrent claim or lifecycle transition makes bind fail and rolls acquisition back.
     */
    fun acquireAndBindForAttemptEnvironment(
        scope: TenantScope,
        environmentId: String,
        expectedRevision: Int,
        attempt: io.whozoss.factory.agentattempt.domain.DurableAgentAttempt,
        bind: (WorktreeExecutionOccupancyHandle) -> Unit,
    ): WorktreeExecutionOccupancyHandle = transaction.execute {
        acquireAndBindInExistingTransaction(scope, environmentId, expectedRevision, attempt, bind)
    }!!

    private fun acquireAndBindInExistingTransaction(
        scope: TenantScope,
        environmentId: String,
        expectedRevision: Int,
        attempt: io.whozoss.factory.agentattempt.domain.DurableAgentAttempt,
        bind: (WorktreeExecutionOccupancyHandle) -> Unit,
    ): WorktreeExecutionOccupancyHandle {
        val environment = environments.findByEnvironmentId(scope, environmentId)
            ?: throw WorktreeExecutionOccupancyResolutionException("Work environment '$environmentId' was not found")
        if (environment.lifecycleState != WorkEnvironmentState.READY) {
            throw WorktreeExecutionOccupancyResolutionException(
                "Work environment '$environmentId' is '${environment.lifecycleState.dbValue}', not READY",
            )
        }
        if (environment.revision != expectedRevision) {
            throw WorktreeExecutionOccupancyResolutionException(
                "Work environment '$environmentId' is at revision ${environment.revision}, expected $expectedRevision",
            )
        }
        val worktree = environment.worktreePath.takeIf { it.isNotBlank() }
            ?: throw WorktreeExecutionOccupancyResolutionException("Work environment has no local worktree path")
        val identity = identities.resolve(Path.of(worktree))
        val ownerId = agentOwnerId(attempt.namespaceId, attempt.workflowId, attempt.stepId, attempt.attemptId)
        val occupied = occupations.acquireJoiningTransaction(scope, identity, AGENT_OWNER_KIND, ownerId)
        val handle = WorktreeExecutionOccupancyHandle(
            environment.environmentId, identity, scope, AGENT_OWNER_KIND, ownerId, occupied.generation,
        )
        bind(handle)
        return handle
    }

    fun release(handle: WorktreeExecutionOccupancyHandle) {
        occupations.release(
            handle.ownerScope,
            handle.resourceIdentity,
            handle.ownerKind,
            handle.ownerId,
            handle.generation,
        )
    }

    /** Releases only the exact durable acquisition captured by an attempt. */
    fun releaseOwned(handle: WorktreeExecutionOccupancyHandle): Boolean = try {
        release(handle)
        true
    } catch (_: WorktreeExecutionOccupancyStaleRelease) {
        false
    }

    private fun acquire(
        scope: TenantScope,
        environment: WorkEnvironment,
        ownerKind: String,
        ownerId: String,
        expectedRepoRoot: Path?,
    ): WorktreeExecutionOccupancyHandle {
        if (environment.organizationId != scope.organizationId || environment.workstreamId != scope.workstreamId) {
            throw WorktreeExecutionOccupancyResolutionException("Work environment scope does not match the owner scope")
        }
        val worktree = environment.worktreePath.takeIf { it.isNotBlank() }
            ?: throw WorktreeExecutionOccupancyResolutionException("Work environment has no local worktree path")
        val identity = try {
            identities.resolve(Path.of(worktree))
        } catch (error: Exception) {
            throw WorktreeExecutionOccupancyResolutionException("Work environment local worktree cannot be resolved: ${error.message}")
        }
        if (expectedRepoRoot != null) {
            val expectedIdentity = try {
                identities.resolve(expectedRepoRoot)
            } catch (error: Exception) {
                throw WorktreeExecutionOccupancyResolutionException("Execution repository root cannot be resolved: ${error.message}")
            }
            if (expectedIdentity != identity) {
                throw WorktreeExecutionOccupancyResolutionException("Execution repository root does not match the persisted worktree")
            }
        }
        val occupation = occupations.acquire(scope, identity, ownerKind, ownerId)
        return WorktreeExecutionOccupancyHandle(
            environment.environmentId,
            identity,
            scope,
            ownerKind,
            ownerId,
            occupation.generation,
        )
    }

    companion object {
        const val AGENT_OWNER_KIND = "agent"

        /** Every durable attempt is a distinct fenced owner, including attempts of the same step. */
        fun agentOwnerId(namespaceId: String, workflowId: String, stepId: String, attemptId: String): String =
            "agent-attempt:$namespaceId#$workflowId#$stepId#$attemptId"
    }
}
