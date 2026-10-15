package io.whozoss.agentos.git

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.git.core.GitCommandResult
import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitExecutionProperties
import io.whozoss.agentos.git.core.GitInvocation
import io.whozoss.agentos.git.core.GitLayout
import io.whozoss.agentos.git.core.GitRefs
import mu.KLogging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID

/** Preparation recovery and local resource cleanup after AgentOS's ordinary soft deletion. */
@Service
@ConditionalOnProperty(prefix = "agentos.git.workspaces", name = ["enabled"], havingValue = "true")
class GitWorkspaceLifecycleService(
    private val bindings: CaseResourceBindingService,
    private val cases: CaseRepository,
    private val caseService: ObjectProvider<CaseService>,
    private val roots: GitExchangeRootResolver,
    private val storage: ExchangeStorageService,
    private val runner: GitCommandRunner,
    private val processes: WorkspaceProcessGuard = WorkspaceProcessGuard(),
    private val gitProperties: GitExecutionProperties = GitExecutionProperties(),
) {
    /**
     * Position of the cleanup sweep. Only the worker's sweep reads and writes it, one pass at a time,
     * so passes on different pool threads always see the last position.
     */
    @Volatile
    private var cleanupCursor: CaseResourceBindingCursor? = null

    fun retry(rootId: UUID): CaseResourceBinding = requested(rootId) {
        val b = requireBinding(rootId)
        if (b.status != CaseResourceStatus.FAILED) throw ConflictException("Only failed preparation can be retried")
        if (b.setup == SetupState.STARTED) throw ConflictException("Setup was interrupted; inspect its effects and acknowledge setup recovery first")
        bindings.markStatus(b.id, CaseResourceStatus.REQUESTED)
    }

    fun acknowledgeSetup(rootId: UUID): CaseResourceBinding = requested(rootId) {
        val b = requireBinding(rootId)
        if (b.status != CaseResourceStatus.FAILED) throw ConflictException("Workspace is not failed")
        // Only an interrupted setup needs its replay acknowledged. A completed one is never run again.
        if (b.setup != SetupState.STARTED) {
            throw ConflictException("No interrupted setup to acknowledge: retry the preparation instead")
        }
        bindings.update(b.copy(setup = SetupState.NOT_STARTED, status = CaseResourceStatus.REQUESTED, failureReason = null))
    }

    /**
     * The existing removed flag is the durable cleanup request. Running in the workspace worker
     * also retries after a restart or while a killed execution is still releasing its tools.
     * PR status and runtime states such as IDLE/KILLED never request removal.
     */
    fun cleanupDeletedCases(stopRequested: () -> Boolean = { Thread.currentThread().isInterrupted }) {
        val statuses = CaseResourceStatus.entries.filter { it != CaseResourceStatus.REMOVED }
        val page = bindings.findByStatusIn(statuses, CLEANUP_BATCH_SIZE, cleanupCursor)
        val batch = if (page.isEmpty() && cleanupCursor != null) bindings.findByStatusIn(statuses, CLEANUP_BATCH_SIZE) else page
        // Advance even when cleanup fails, so blocked workspaces cannot starve later families.
        // At the end, start a new sweep to retry earlier rows and observe newly deleted cases.
        cleanupCursor = batch.takeIf { it.size == CLEANUP_BATCH_SIZE }?.last()?.let(CaseResourceBindingCursor::after)
        batch.forEach { binding ->
            if (stopRequested()) return
            try {
                cleanupDeleted(binding.rootCaseId)
            } catch (e: Exception) {
                logger.warn(e) { "Could not clean worktree of deleted case ${binding.rootCaseId}" }
            }
        }
    }

    /** Removed members may still be stopping, and only a case with a runtime in this process can be. */
    private fun stoppingMembers(
        service: CaseService,
        root: Case,
    ): List<UUID> {
        val withRuntime = service.getAllActiveCases().filter { it.namespaceId == root.namespaceId }.map { it.id }
        if (withRuntime.isEmpty()) return listOf(root.id)
        return listOf(root.id) + roots.familyAmong(root, cases.findByIds(withRuntime, withRemoved = true)).map { it.id }
    }

    fun cleanupDeleted(rootId: UUID): CaseResourceBinding {
        // Deletion is final, so read the root once, outside the lock: a live family's lock is never held.
        val root = cases.findByIds(listOf(rootId), withRemoved = true).firstOrNull()
        if (root == null || !root.metadata.removed) return requireBinding(rootId)
        return locked(rootId) { cleanupRemoved(root) }
    }

    private fun cleanupRemoved(root: Case): CaseResourceBinding {
        val rootId = root.id
        var b = requireBinding(rootId)
        if (b.status == CaseResourceStatus.REMOVED) return b
        // Deleting a case never cascaded to its children. Their shared files must remain usable.
        if (roots.familyAmong(root, cases.findByParent(root.namespaceId)).isNotEmpty()) return b
        b = bindings.update(b.copy(status = CaseResourceStatus.DELETING))
        var reason = "Cannot confirm that the deleted case has stopped its execution."
        return try {
            if (caseService.getObject().let { it.hasRunningExecutions(stoppingMembers(it, root)) }) {
                throw ConflictException("A deleted case is still stopping its execution")
            }
            reason = "Cannot resolve the deleted case's workspace directory."
            val path = roots.resolveGit(root).repositoryPath.toAbsolutePath().normalize()
            reason = "Cannot confirm that local Git configuration is safe for cleanup."
            if (Files.isDirectory(commonGitDir(b))) runner.assertNoHostileLocalConfig(commonGitDir(b))
            if (Files.exists(path)) {
                reason = "Workspace process inspection did not confirm an idle directory. Check running processes and lsof availability."
                // Background jobs, tmux shells or MCP servers an agent left in the worktree.
                processes.stopProcesses(path)
                processes.assertIdle(path)
                val admin = commonGitDir(b).worktreeRegistration(rootId)
                if (isInterruptedRemoval(b, admin, rootId)) {
                    // Only this service writes the marker, after the inspection below passed and the
                    // commit was retained: what is missing now is what the killed removal deleted.
                    reason = "Cannot finish a worktree removal that was interrupted."
                    deleteTreeWithoutFollowingLinks(path)
                    deleteTreeWithoutFollowingLinks(admin)
                } else {
                    removeWorktree(b, admin, path, rootId)
                }
            } else if (Files.isDirectory(commonGitDir(b))) {
                // Reconcile only this deleted family's registration. Other checkouts may merely
                // be temporarily unavailable and still own indexes, refs and submodule metadata.
                reason = "Cannot safely remove the missing worktree registration. Inspect its index, locks and submodules."
                removeMissingWorktreeRegistration(runner, commonGitDir(b), rootId, path)
            }
            val support = storage.workspaceSupportDirectory(b.namespaceId, rootId)
            if (Files.exists(support)) {
                reason = "Workspace support files are still in use or cannot be deleted."
                processes.stopProcesses(support)
                processes.assertIdle(support)
                // Setup can create symlinks. Delete links themselves, never their external targets.
                deleteTreeWithoutFollowingLinks(support)
            }
            bindings.update(b.copy(status = CaseResourceStatus.REMOVED, cleanupReason = null))
        } catch (e: Exception) {
            val recorded = (e as? CleanupStepFailed)?.reason ?: reason
            if (b.cleanupReason != recorded) logger.warn(e) { "Worktree of deleted case $rootId retained: $recorded" }
            bindings.update(b.copy(cleanupReason = recorded))
        }
    }

    /** Each step fails with the reason recorded for the user, never Git's own output. */
    private fun removeWorktree(
        b: CaseResourceBinding,
        admin: Path,
        path: Path,
        rootId: UUID,
    ) {
        // Removal honours status.showUntrackedFiles. Inspect explicitly so a display preference
        // cannot make cleanup discard ordinary untracked agent work. Submodule contents are not
        // inspected: that would run their own filters, and a non-forced removal refuses them anyway.
        val dirty = cleanupStep("Cannot inspect local worktree changes before cleanup.") {
            runner.runOrThrow(GitInvocation(
                listOf("--no-optional-locks", "status", "--porcelain", "--untracked-files=all", "--ignore-submodules=dirty"),
                gitDir = admin, workTree = path,
            ))
        }
        if (dirty.isNotEmpty()) throw CleanupStepFailed("The worktree contains uncommitted or untracked work.")
        // An ignored directory can hold an agent's own linked worktree, invisible to the status above.
        cleanupStep("The worktree contains another Git worktree. Remove it or move it out of the workspace first.") {
            check(nestedWorktrees(b, path).isEmpty()) { "The worktree contains another Git worktree." }
        }
        // A detached HEAD is normal here. Worktree removal drops its reflog as well;
        // retain the commit before removal without creating a user branch or a PR.
        cleanupStep("Cannot preserve the worktree's current commit before cleanup.") {
            val head = runner.runOrThrow(GitInvocation(listOf("rev-parse", "--verify", "HEAD^{commit}"), gitDir = admin))
            runner.runOrThrow(GitInvocation(
                listOf("update-ref", "--no-deref", GitRefs.AGENTOS_RETAINED + rootId, head), gitDir = commonGitDir(b),
            ))
        }
        // Git's remaining safety checks still retain locked worktrees. Documents stay.
        cleanupStep("Git refused to remove the worktree. Inspect its locks and submodules.") {
            val marker = admin.resolve(REMOVAL_MARKER)
            Files.writeString(marker, "$rootId\n")
            val removal = runner.run(GitInvocation(listOf("worktree", "remove", path.toString()),
                gitDir = commonGitDir(b), timeout = gitProperties.cloneTimeout))
            // A refusal changed nothing: forget the marker. A killed or timed-out removal keeps it.
            if (removal is GitCommandResult.Completed && !removal.successful) Files.deleteIfExists(marker)
            check(removal is GitCommandResult.Completed && removal.successful) { "Git did not remove the worktree" }
        }
    }

    /** Run one cleanup step, turning any failure into [reason]. */
    private inline fun <T> cleanupStep(
        reason: String,
        action: () -> T,
    ): T =
        try {
            action()
        } catch (e: Exception) {
            throw CleanupStepFailed(reason, e)
        }

    /** A cleanup step that failed, with the reason recorded on the binding for the user. */
    private class CleanupStepFailed(
        val reason: String,
        cause: Throwable? = null,
    ) : RuntimeException(reason, cause)

    /**
     * A removal killed midway (for example by a redeploy) leaves a half-deleted worktree that every
     * later inspection reports as uncommitted work. It is recognized only when the service's own
     * marker is present, the commit retained before removal is still the worktree's HEAD, and no
     * lock or submodule could hold work the inspection did not cover.
     */
    private fun isInterruptedRemoval(b: CaseResourceBinding, admin: Path, rootId: UUID): Boolean {
        if (!Files.isRegularFile(admin.resolve(REMOVAL_MARKER), NOFOLLOW_LINKS)) return false
        if (Files.exists(admin.resolve(GitLayout.LOCKED_FILE), NOFOLLOW_LINKS) || Files.exists(admin.resolve(GitLayout.MODULES_DIR), NOFOLLOW_LINKS)) {
            return false
        }
        val retained = runner.run(GitInvocation(
            listOf("rev-parse", "--verify", "--quiet", "${GitRefs.AGENTOS_RETAINED}$rootId^{commit}"), gitDir = commonGitDir(b),
        ))
        val head = runner.run(GitInvocation(listOf("rev-parse", "--verify", "--quiet", "HEAD^{commit}"), gitDir = admin))
        return retained is GitCommandResult.Completed && retained.successful &&
            head is GitCommandResult.Completed && head.successful &&
            retained.stdout.trim() == head.stdout.trim()
    }

    /** Linked worktrees registered inside [path]: a non-forced removal of [path] would delete them. */
    private fun nestedWorktrees(b: CaseResourceBinding, path: Path): List<Path> {
        val root = path.toRealPath()
        return runner.runOrThrow(GitInvocation(listOf("worktree", "list", "--porcelain"), gitDir = commonGitDir(b)))
            .lineSequence()
            .filter { it.startsWith("worktree ") }
            .map { Path.of(it.removePrefix("worktree ")) }
            .map { runCatching { it.toRealPath() }.getOrElse { _ -> it.toAbsolutePath().normalize() } }
            .filter { it != root && it.startsWith(root) }
            .toList()
    }

    private fun commonGitDir(binding: CaseResourceBinding) =
        storage.namespaceGitDirectory(binding.namespaceId).toAbsolutePath().normalize()

    private fun requireBinding(id: UUID): CaseResourceBinding = bindings.findByRootCaseId(id)
        ?: throw ConflictException("Only the owning root case can retry workspace preparation")
    private fun <T> requested(id: UUID, action: () -> T): T = WorkspaceLifecycleLocks.tryWithRoot(
        id,
        onBusy = { throw ConflictException("The workspace is busy; retry after its current operation completes") },
        action = action,
    )
    private fun <T> locked(id: UUID, action: () -> T): T = WorkspaceLifecycleLocks.withRoot(id, action)
    companion object : KLogging() {
        /** Written in the registration right before `worktree remove`, which deletes it on success. */
        internal const val REMOVAL_MARKER = "agentos-removal-started"

        /** Bindings inspected per worker tick, so a slow cleanup never holds the sweep for long. */
        private const val CLEANUP_BATCH_SIZE = 5
    }
}
