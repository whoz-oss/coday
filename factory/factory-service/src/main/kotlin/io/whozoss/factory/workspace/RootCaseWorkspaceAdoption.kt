package io.whozoss.factory.workspace

import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.environment.port.BoundedGitCommandRunner
import io.whozoss.factory.environment.port.GitCommandRunner
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.run.service.RunProvisioningTiming
import io.whozoss.factory.worktree.LocalWorktreeResourceIdentityResolver
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

class WorkspaceAdoptionUnavailableException(message: String) : RuntimeException(message)

class TerminalWorkspaceAdoptionException(message: String) : IllegalStateException(message)
class TransientWorkspaceAdoptionException(cause: Exception) : RuntimeException("Workspace adoption temporarily unavailable; intent retained", cause)

/** Recognized transport failures only; malformed responses/programming errors are not retry policies. */
fun isTemporaryWorkspaceTransportFailure(error: Exception): Boolean {
    val causes = generateSequence<Throwable>(error) { it.cause }.toList()
    val response = causes.filterIsInstance<org.springframework.web.client.RestClientResponseException>().firstOrNull()
    if (response != null) return response.statusCode.is5xxServerError || response.statusCode.value() in setOf(408, 429)
    return causes.any { it is java.io.IOException || it is org.springframework.web.client.ResourceAccessException }
}

/** AgentOS owns creation/setup. Shared by parent Runs and standalone workflows; never creates Git resources. */
class RootCaseWorkspaceAdoption(
    private val environments: WorkEnvironmentRepository,
    private val proxy: AgentOsProxyClient,
    transactionManager: PlatformTransactionManager,
    private val timing: RunProvisioningTiming,
    private val git: GitCommandRunner = BoundedGitCommandRunner(),
) {
    private val transaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    fun awaitReady(scope: TenantScope, reserved: WorkEnvironment, actorId: String, inspectGit: Boolean = false, singleProbe: Boolean = false): WorkEnvironment {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Workspace adoption must run outside a caller transaction" }
        require(actorId.isNotBlank()) { "Authenticated provisioning identity is required" }
        val root = requireNotNull(reserved.parentCaseId)
        val started = timing.nanoTime()
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Root workspace provisioning interrupted; intent retained")
            val raw = proxy.fetchWorkspaceChanges(root, actorId)
                ?: throw WorkspaceAdoptionUnavailableException("Root workspace read unavailable; intent retained")
            invariant(raw["equipped"] == true) { "Root case is not equipped with a workspace" }
            when (raw["status"]) {
                "READY" -> {
                    available(raw["error"] == null) { "READY workspace Git inspection failed" }
                    val path = (raw["path"] as? String)?.takeIf { it.isNotBlank() }
                        ?: throw TerminalWorkspaceAdoptionException("READY workspace has no path")
                    val local = try { Path.of(path) } catch (_: java.nio.file.InvalidPathException) {
                        throw TerminalWorkspaceAdoptionException("Root workspace path is invalid")
                    }
                    invariant(local.isAbsolute) { "Root workspace path must be absolute" }
                    val identity = LocalWorktreeResourceIdentityResolver().resolve(local)
                    val canonical = local.toRealPath()
                    val head = if (inspectGit) {
                        invariant(Files.exists(canonical.resolve(".git"))) { "Root workspace is not an exact Git root" }
                        invariant(Path.of(read(canonical, listOf("rev-parse", "--show-toplevel"))).toRealPath() == canonical) { "Root workspace Git root mismatch" }
                        read(canonical, listOf("rev-parse", "--verify", "HEAD")).also { invariant(SHA.matches(it)) { "Root workspace HEAD is invalid" } }
                    } else null
                    val observedBase = (raw["changes"] as? Map<*, *>)?.get("base") as? String
                    invariant(observedBase == null || SHA.matches(observedBase)) { "Root workspace baseline is invalid" }
                    val baseline = observedBase ?: head
                    if (inspectGit && baseline != null) {
                        ancestor(canonical, baseline, head!!, "Root workspace baseline does not precede HEAD")
                    }
                    val branch = if (inspectGit) git.run(canonical, listOf("symbolic-ref", "--quiet", "--short", "HEAD"))
                        .let { it.stdout.trim().takeIf { name -> it.exitCode == 0 && name.isNotBlank() } }
                    else (raw["branch"] as? String)?.takeIf { it.isNotBlank() }
                    // Inspect the persisted baseline before entering the short write transaction.
                    if (inspectGit && reserved.baseCommit != null) {
                        ancestor(canonical, reserved.baseCommit, head!!, "Root workspace HEAD diverged from adopted baseline")
                    }
                    return transaction.execute {
                        val current = environments.findByEnvironmentId(scope, reserved.environmentId) ?: error("Provisioning intent disappeared")
                        invariant(current.workflowId == reserved.workflowId && current.namespaceId == reserved.namespaceId &&
                            current.parentCaseId == root && current.createdBy == reserved.createdBy && current.envType == reserved.envType) { "Root workspace environment identity mismatch" }
                        invariant(current.provisioningFailure == null) { "Root workspace provisioning previously failed" }
                        when (current.lifecycleState) {
                            WorkEnvironmentState.READY -> {
                                invariant(LocalWorktreeResourceIdentityResolver().resolve(Path.of(current.worktreePath)) == identity) { "Root workspace changed physical identity" }
                                // READY replay is read-only: never replace branch/baseCommit with today's HEAD.
                                current
                            }
                            WorkEnvironmentState.PROVISIONING -> environments.updateState(scope, current.copy(
                                worktreePath = canonical.toString(), repoRoot = canonical.toString(),
                                branch = branch,
                                baseCommit = baseline,
                                lifecycleState = WorkEnvironmentState.READY,
                            ), current.revision, Instant.now())
                            else -> throw TerminalWorkspaceAdoptionException("Root workspace environment is busy or decommissioned")
                        }
                    }!!
                }
                "REQUESTED", "PREPARING" -> if (singleProbe) {
                    throw WorkspaceAdoptionUnavailableException("Root workspace not ready; intent retained")
                }
                "FAILED" -> throw TerminalWorkspaceAdoptionException("Root workspace preparation failed")
                else -> throw TerminalWorkspaceAdoptionException("Root workspace has unknown status: ${raw["status"]}")
            }
            available(timing.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(timing.timeoutMs)) { "Root workspace provisioning timed out; intent retained" }
            try { timing.pause(timing.pollIntervalMs) } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
        }
    }

    private fun available(valid: Boolean, message: () -> String) {
        if (!valid) throw WorkspaceAdoptionUnavailableException(message())
    }

    private fun invariant(valid: Boolean, message: () -> String) {
        if (!valid) throw TerminalWorkspaceAdoptionException(message())
    }

    private fun ancestor(root: Path, base: String, head: String, message: String) {
        val exit = git.run(root, listOf("merge-base", "--is-ancestor", base, head)).exitCode
        // Git's explicit negative ancestry result is permanent. An inspection failure
        // (missing object, temporarily inaccessible repository, timeout) is not that proof.
        invariant(exit != 1) { message }
        available(exit == 0) { "Root workspace ancestry inspection unavailable" }
    }

    private fun read(root: Path, args: List<String>): String {
        val result = git.run(root, args)
        available(result.exitCode == 0) { "Cannot inspect root workspace Git ${args.first()}" }
        return result.stdout.trim()
    }

    private companion object { val SHA = Regex("^[0-9a-fA-F]{40}$") }
}
