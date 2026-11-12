package io.whozoss.factory.run.service

import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.run.domain.RunRecord
import io.whozoss.factory.run.persistence.RunRepository
import io.whozoss.factory.worktree.LocalWorktreeResourceIdentityResolver
import io.whozoss.factory.run.persistence.RunProvisioningIntent
import io.whozoss.factory.run.persistence.RunProvisioningIntentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Path
import java.time.Instant

@org.springframework.stereotype.Component
open class RunProvisioningTiming(
    @param:org.springframework.beans.factory.annotation.Value("\${factory.run.provisioning.timeout-ms:60000}") val timeoutMs: Long = 60_000,
    @param:org.springframework.beans.factory.annotation.Value("\${factory.run.provisioning.poll-interval-ms:250}") val pollIntervalMs: Long = 250,
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
    private val mapper: com.fasterxml.jackson.databind.ObjectMapper,
    transactionManager: PlatformTransactionManager,
    private val timing: RunProvisioningTiming = RunProvisioningTiming(),
) {
    private val transaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW
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
        val hash = io.whozoss.factory.agentattempt.domain.CanonicalJsonHash.hash(mapper.valueToTree(
            listOf(command.runType, command.title, command.ticket, command.initialDomainState, command.maxTotalFragments, repoRoot),
        ))
        val root = requireNotNull(proposed.rootCaseId) { "Run has no persisted root case identity" }
        val environmentId = "run-workspace:" + java.util.UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8))
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
        val started = timing.nanoTime()
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Run provisioning interrupted; intent retained")
            val raw = proxy.fetchWorkspaceChanges(root, actorId) ?: error("Root workspace is unavailable")
            check(raw["equipped"] == true) { "Root case is not equipped with a workspace" }
            when (raw["status"]) {
                "READY" -> {
                    check(raw["error"] == null) { "READY workspace Git inspection failed" }
                    val path = (raw["path"] as? String)?.takeIf { it.isNotBlank() } ?: error("READY workspace has no path")
                    val identity = LocalWorktreeResourceIdentityResolver().resolve(Path.of(path))
                    transaction.execute {
                        val current = environments.findByEnvironmentId(scope, environmentId) ?: error("Provisioning intent disappeared")
                        check(current.lifecycleState != WorkEnvironmentState.DECOMMISSIONED) { "Run environment was decommissioned" }
                        if (current.lifecycleState == WorkEnvironmentState.READY) {
                            check(LocalWorktreeResourceIdentityResolver().resolve(Path.of(current.worktreePath)) == identity) { "Root workspace changed physical identity" }
                        } else {
                            check(current.lifecycleState == WorkEnvironmentState.PROVISIONING)
                            environments.updateState(scope, current.copy(
                                worktreePath = path, repoRoot = path,
                                branch = (raw["branch"] as? String)?.takeIf { it.isNotBlank() },
                                baseCommit = ((raw["changes"] as? Map<*, *>)?.get("base") as? String)
                                    ?.takeIf { Regex("^[0-9a-fA-F]{40}$").matches(it) },
                                lifecycleState = WorkEnvironmentState.READY,
                            ), current.revision, Instant.now())
                        }
                    }
                    return run
                }
                "REQUESTED", "PREPARING" -> Unit
                else -> error("Root workspace preparation failed or has unknown status: ${raw["status"]}")
            }
            check(timing.nanoTime() - started < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timing.timeoutMs)) { "Root workspace provisioning timed out; intent retained" }
            try {
                timing.pause(timing.pollIntervalMs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
        }
    }
}
