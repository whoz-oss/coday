package io.whozoss.factory.run.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.agentattempt.domain.CanonicalJsonHash
import org.springframework.stereotype.Component
import org.springframework.beans.factory.annotation.Value
import org.springframework.transaction.TransactionDefinition
import java.util.UUID
import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.run.domain.RunRecord
import io.whozoss.factory.run.persistence.RunRepository
import io.whozoss.factory.run.persistence.RunProvisioningIntent
import io.whozoss.factory.run.persistence.RunProvisioningIntentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager

@Component
open class RunProvisioningTiming(
    @param:Value("\${factory.run.provisioning.timeout-ms:60000}") val timeoutMs: Long = 60_000,
    @param:Value("\${factory.run.provisioning.poll-interval-ms:250}") val pollIntervalMs: Long = 250,
) {
    init { require(timeoutMs >= 0 && pollIntervalMs > 0) }
    open fun nanoTime(): Long = System.nanoTime()
    open fun pause(milliseconds: Long) = Thread.sleep(milliseconds)
}

/** AgentOS alone owns worktree creation and setup. Factory only adopts verified facts. */
@Service
class ParentRunWorkspaceProvisioningService(
    private val runs: RunRepository,
    private val environments: WorkEnvironmentRepository,
    private val intents: RunProvisioningIntentRepository,
    private val proxy: AgentOsProxyClient,
    private val mapper: ObjectMapper,
    transactionManager: PlatformTransactionManager,
    private val timing: RunProvisioningTiming = RunProvisioningTiming(),
) {
    private val adoption = io.whozoss.factory.workspace.RootCaseWorkspaceAdoption(environments, proxy, transactionManager, timing)
    private val transaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    fun readyPath(scope: TenantScope, run: RunRecord): String {
        val environment = environments.findLatestByWorkflowId(scope, run.runId) ?: error("Run environment disappeared")
        check(environment.namespaceId == run.namespaceId && environment.parentCaseId == run.rootCaseId && environment.lifecycleState == WorkEnvironmentState.READY)
        return environment.worktreePath.takeIf { it.isNotBlank() } ?: error("READY worktree has no path")
    }

    fun ensureReady(scope: TenantScope, proposed: RunRecord, command: RunStartCommand, repoRoot: String?, actorId: String): RunRecord {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Run provisioning interrupted before reservation")
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Provisioning must run outside a caller transaction" }
        require(actorId.isNotBlank()) { "Authenticated provisioning identity is required" }
        val key = mapper.writeValueAsString(listOf(scope.organizationId, scope.workstreamId, proposed.namespaceId, proposed.runId))
        require(proposed.runId == command.runId && proposed.runType == command.runType && proposed.namespaceId.isNotBlank()) { "Run command identity mismatch" }
        val hash = CanonicalJsonHash.hash(mapper.valueToTree(
            listOf(command.runType, command.title, command.ticket, command.initialDomainState, command.maxTotalFragments, repoRoot),
        ))
        val root = requireNotNull(proposed.rootCaseId) { "Run has no persisted root case identity" }
        val environmentId = "run-workspace:" + UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8))
        val run = transaction.execute {
            val intent = intents.findById(key).orElse(null)
            if (intent != null) {
                check(intent.actorId == actorId && intent.commandHash == hash && intent.rootCaseId == root) { "Run launch identity differs from persisted intent" }
            } else {
                // Legacy Runs require an explicit migration, never silently invented attribution.
                check(runs.find(scope, proposed.namespaceId, proposed.runId) == null) { "Existing Run has no authenticated provisioning intent; explicit migration required" }
                intents.save(RunProvisioningIntent(id = key, actorId = actorId, commandHash = hash, rootCaseId = root))
            }
            val saved = runs.find(scope, proposed.namespaceId, proposed.runId) ?: runs.insert(scope, proposed)
            check(saved.rootCaseId == root) { "Run root case changed" }
            val existing = environments.findByEnvironmentId(scope, environmentId)
            if (existing == null) {
                check(environments.findLatestByWorkflowId(scope, saved.runId) == null) { "Run already has another environment; explicit adoption required" }
                environments.insert(scope, WorkEnvironment(
                    organizationId = scope.organizationId, workstreamId = scope.workstreamId,
                    environmentId = environmentId, workUnitId = saved.runId, workflowId = saved.runId,
                    namespaceId = saved.namespaceId, parentCaseId = root, repoRoot = "",
                    integrationBranch = "", branch = null, worktreePath = "", createdBy = actorId,
                ))
            } else check(existing.namespaceId == saved.namespaceId && existing.parentCaseId == root && existing.workflowId == saved.runId) { "Run environment identity mismatch" }
            saved
        }!!
        proxy.ensureRunRootCase(root, run.namespaceId, run.title, actorId)
        adoption.awaitReady(scope, environments.findByEnvironmentId(scope, environmentId)!!, actorId)
        return run
    }
}
