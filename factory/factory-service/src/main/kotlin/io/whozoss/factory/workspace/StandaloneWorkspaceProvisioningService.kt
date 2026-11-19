package io.whozoss.factory.workspace

import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.run.service.RunProvisioningTiming
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Durable adoption intent for standalone agent -> code workflows, not a Git provisioner. */
@Service
class StandaloneWorkspaceProvisioningService(
    private val environments: WorkEnvironmentRepository,
    private val proxy: AgentOsProxyClient,
    transactionManager: PlatformTransactionManager,
    timing: RunProvisioningTiming,
    private val properties: WorkspaceProperties,
) {
    private val transaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
    private val adoption = RootCaseWorkspaceAdoption(environments, proxy, transactionManager, timing)

    val enabled: Boolean get() = properties.enabled

    fun ensureReady(scope: TenantScope, namespaceId: String, workflowId: String, rootCaseId: String, title: String, actorId: String?) {
        if (!properties.enabled) return
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Standalone adoption must run outside a caller transaction" }
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Workspace adoption interrupted")
        require(!actorId.isNullOrBlank()) { "Authenticated provisioning identity is required" }
        val key = listOf(scope.organizationId, scope.workstreamId, namespaceId, workflowId).joinToString("|")
        locks.computeIfAbsent(key) { ReentrantLock() }.withLock {
            val id = "workflow-workspace:" + UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8))
            val reserved = transaction.execute {
                val matches = environments.list(scope).filter { it.workflowId == workflowId }
                check(matches.size <= 1) { "Workflow has conflicting environments; explicit adoption required" }
                val current = matches.singleOrNull()
                if (current == null) {
                    check(environments.findByEnvironmentId(scope, id) == null) { "Workspace environment identity collision" }
                    environments.insert(scope, WorkEnvironment(
                        scope.organizationId, scope.workstreamId, id, workflowId, workflowId, namespaceId,
                        rootCaseId, "", "", null, "", createdBy = actorId, envType = ENV_TYPE,
                    ))
                } else {
                    check(current.environmentId == id && current.namespaceId == namespaceId && current.parentCaseId == rootCaseId &&
                        current.createdBy == actorId && current.envType == ENV_TYPE) { "Workflow workspace owner identity mismatch; explicit adoption required" }
                    check(current.provisioningFailure == null) { "Workflow workspace provisioning previously failed: ${current.provisioningFailure}" }
                    check(current.lifecycleState in setOf(WorkEnvironmentState.PROVISIONING, WorkEnvironmentState.READY)) { "Workflow workspace is busy or decommissioned" }
                    current
                }
            }!!
            try {
                proxy.ensureRunRootCase(rootCaseId, namespaceId, title, actorId)
                adoption.awaitReady(scope, reserved, actorId, inspectGit = true)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            } catch (error: TerminalWorkspaceAdoptionException) {
                // Explicit terminal status or a proven identity/Git invariant failure.
                // Unavailable HTTP, readiness timeouts and READY inspection errors retain intent.
                transaction.execute {
                    val current = environments.findByEnvironmentId(scope, id) ?: error("Workspace intent disappeared")
                    if (current.lifecycleState == WorkEnvironmentState.PROVISIONING && current.provisioningFailure == null &&
                        current.workflowId == reserved.workflowId && current.namespaceId == reserved.namespaceId &&
                        current.parentCaseId == reserved.parentCaseId && current.createdBy == reserved.createdBy &&
                        current.envType == reserved.envType) {
                        environments.updateState(scope, current.copy(provisioningFailure = "ROOT_WORKSPACE_ADOPTION_FAILED"), current.revision, Instant.now())
                    }
                }
                throw error
            } catch (error: Exception) {
                throw TransientWorkspaceAdoptionException(error)
            }
        }
    }

    companion object {
        const val ENV_TYPE = "agentos-root-workspace"
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
    }
}
