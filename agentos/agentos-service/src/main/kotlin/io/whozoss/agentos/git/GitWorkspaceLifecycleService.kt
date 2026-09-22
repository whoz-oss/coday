package io.whozoss.agentos.git

import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.sdk.tool.WorkspaceToolLifecycle
import io.whozoss.agentos.tool.ToolRegistryService
import mu.KLogging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.util.UUID

/** Preparation recovery and local resource cleanup after AgentOS's ordinary soft deletion. */
@Service
class GitWorkspaceLifecycleService(
    private val bindings: CaseResourceBindingService,
    private val cases: CaseRepository,
    private val caseService: ObjectProvider<CaseService>,
    private val roots: ExchangeRootResolver,
    private val storage: ExchangeStorageService,
    private val runner: GitCommandRunner,
    private val plugins: ToolRegistryService,
    private val processes: WorkspaceProcessGuard = WorkspaceProcessGuard(),
) {
    private var cleanupOffset = 0

    fun retry(rootId: UUID): CaseResourceBinding = locked(rootId) {
        val b = requireBinding(rootId)
        if (b.status != CaseResourceStatus.FAILED) throw ConflictException("Only failed preparation can be retried")
        if (b.setupStarted && !b.setupCompleted) throw ConflictException("Setup was interrupted; inspect its effects and acknowledge setup recovery first")
        bindings.markStatus(b.id, CaseResourceStatus.REQUESTED)
    }

    fun acknowledgeSetup(rootId: UUID): CaseResourceBinding = locked(rootId) {
        val b = requireBinding(rootId)
        if (b.status != CaseResourceStatus.FAILED) throw ConflictException("Workspace is not failed")
        bindings.update(b.copy(setupStarted = false, setupCompleted = false, status = CaseResourceStatus.REQUESTED, failureReason = null))
    }

    /**
     * The existing removed flag is the durable cleanup request. Running in the workspace worker
     * also retries after a restart or while a killed execution is still releasing its tools.
     * PR status and runtime states such as IDLE/KILLED never request removal.
     */
    fun cleanupDeletedCases() {
        val candidates = bindings.findByStatusIn(CaseResourceStatus.entries.filter { it != CaseResourceStatus.REMOVED }, 10000)
        if (candidates.isEmpty()) return
        if (cleanupOffset >= candidates.size) cleanupOffset = 0
        val batch = candidates.drop(cleanupOffset).take(5)
        cleanupOffset += batch.size
        batch.forEach { binding ->
            try {
                cleanupDeleted(binding.rootCaseId)
            } catch (e: Exception) {
                logger.warn(e) { "Could not clean worktree of deleted case ${binding.rootCaseId}" }
            }
        }
    }

    fun cleanupDeleted(rootId: UUID): CaseResourceBinding = locked(rootId) {
        var b = requireBinding(rootId)
        if (b.status == CaseResourceStatus.REMOVED) return@locked b
        val root = cases.findByIds(listOf(rootId), withRemoved = true).firstOrNull() ?: return@locked b
        if (!root.metadata.removed) return@locked b
        val members = roots.familyMembers(root)
        // Deleting a case never cascaded to its children. Their shared files must remain usable.
        if (members.any { !it.metadata.removed }) return@locked b
        b = bindings.update(b.copy(status = CaseResourceStatus.DELETING))
        try {
            if (caseService.getObject().hasRunningExecutions(members.map { it.id })) {
                throw ConflictException("A deleted case is still stopping its execution")
            }
            val path = roots.resolve(root).repositoryPath.toAbsolutePath().normalize()
            listOf("BASH", "TMUX", "MCP_STDIO").forEach { type ->
                (plugins.findPlugin(type) as? WorkspaceToolLifecycle)?.releaseWorkspace(rootId.toString(), path.toString())
            }
            if (Files.isDirectory(commonGitDir(b))) runner.assertNoHostileLocalConfig(commonGitDir(b))
            if (Files.exists(path)) {
                processes.assertIdle(path)
                // Use Git's ordinary removal checks: dirty or locked worktrees are retained.
                // Only repo/ is removed. Exchange documents and local/remote branches stay intact.
                runner.runOrThrow(GitInvocation(listOf("worktree", "remove", path.toString()), gitDir = commonGitDir(b)))
            } else if (Files.isDirectory(commonGitDir(b))) {
                // Reconcile a crash after removal and before saving REMOVED, or incomplete creation.
                runner.runOrThrow(GitInvocation(listOf("worktree", "prune"), gitDir = commonGitDir(b)))
            }
            bindings.update(b.copy(status = CaseResourceStatus.REMOVED, cleanupReason = null))
        } catch (e: Exception) {
            val reason = e.message?.take(1000) ?: "Worktree cleanup failed"
            if (b.cleanupReason != reason) logger.warn { "Worktree of deleted case $rootId retained: $reason" }
            bindings.update(b.copy(cleanupReason = reason))
        }
    }

    private fun commonGitDir(binding: CaseResourceBinding) =
        storage.namespaceGitDirectory(binding.namespaceId).toAbsolutePath().normalize()

    private fun requireBinding(id: UUID): CaseResourceBinding = bindings.findByRootCaseId(id)
        ?: throw ConflictException("Only the owning root case can retry workspace preparation")
    private fun <T> locked(id: UUID, action: () -> T): T = WorkspaceLifecycleLocks.withRoot(id, action)
    companion object : KLogging()
}
