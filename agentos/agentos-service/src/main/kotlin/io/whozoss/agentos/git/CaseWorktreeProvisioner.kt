package io.whozoss.agentos.git

import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.git.core.GitCommandException
import io.whozoss.agentos.git.core.GitCommandResult
import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitExecutionProperties
import io.whozoss.agentos.git.core.GitInvocation
import mu.KLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/** Creates a detached worktree at a frozen base. Branches and PRs belong to agent workflows. */
@Service
class CaseWorktreeProvisioner(
    private val runner: GitCommandRunner,
    private val gitProperties: GitExecutionProperties,
    private val exchangeStorageService: ExchangeStorageService,
    private val bindingService: CaseResourceBindingService,
    private val checkoutProvisioner: RepositoryCheckoutProvisioner,
    private val serviceAccountResolver: GitServiceAccountResolver,
    private val setupRunner: WorktreeSetupRunner,
) {
    /**
     * Bring [binding] to [CaseResourceStatus.READY], creating whatever is still missing.
     *
     * @throws GitCommandException when git refuses a step
     * @throws IllegalStateException when the target directory is occupied by something else
     */
    fun ensureReady(
        binding: CaseResourceBinding,
        settings: GitRepositorySettings,
        rootCase: Case,
    ): CaseResourceBinding {
        // Persist the failing operation, never subprocess output or exception messages.
        var failureReason = "Cannot validate the existing worktree. Inspect its location and Git metadata before retrying."
        return try {
            val worktreePath = worktreePath(rootCase)
            val commonGitDir = exchangeStorageService.namespaceGitDirectory(settings.namespaceId).toAbsolutePath().normalize()
            if (binding.status == CaseResourceStatus.READY && isWorktree(worktreePath, commonGitDir)) return binding
            bindingService.markStatus(binding.id, CaseResourceStatus.PREPARING)
            failureReason = "Cannot prepare the namespace repository. Check its Git settings and service account."
            val checkout = checkoutProvisioner.ensureReady(settings)
            check(checkout.status == RepositoryCheckoutStatus.READY) {
                "The namespace checkout is ${checkout.status}; a case workspace cannot be derived from it yet"
            }
            val gitDir =
                exchangeStorageService
                    .namespaceGitDirectory(settings.namespaceId)
                    .toAbsolutePath()
                    .normalize()

            failureReason = "Cannot fetch the case base. Check repository access, the main branch and local Git configuration."
            val withBase = freezeBaseSha(binding, settings, gitDir)
            failureReason = "Cannot create or recover the worktree. Inspect its directory and Git registration before retrying."
            setAsideInterruptedCheckout(withBase, gitDir, worktreePath)
            addWorktree(withBase, gitDir, worktreePath)
            val current = bindingService.findByRootCaseId(binding.rootCaseId) ?: withBase
            if (!current.setupCompleted) {
                failureReason = "Setup was interrupted; inspect its effects and acknowledge its replay before retrying."
                check(!current.setupStarted) { "Setup was interrupted; acknowledge its replay before retrying" }
                bindingService.update(current.copy(setupStarted = true))
                failureReason = "Setup failed or was interrupted. Inspect the setup command and its effects before retrying."
                setupRunner.run(settings, worktreePath,
                    exchangeStorageService.workspaceSupportDirectory(binding.namespaceId, binding.rootCaseId))
                val afterSetup = bindingService.findByRootCaseId(binding.rootCaseId) ?: current
                bindingService.update(afterSetup.copy(setupCompleted = true))
            }

            bindingService.markStatus(withBase.id, CaseResourceStatus.READY)
        } catch (e: Exception) {
            logger.error { "Workspace ${binding.rootCaseId}: $failureReason (${e.javaClass.simpleName})" }
            bindingService.markStatus(
                binding.id,
                CaseResourceStatus.FAILED,
                failureReason = failureReason,
            )
            throw e
        }
    }

    /**
     * Where the family's worktree lives: repo/ in the root case's Exchange directory.
     *
     * Absolute, because git records the worktree location in its own metadata and resolves it
     * from processes whose working directory is not the JVM's.
     */
    fun worktreePath(rootCase: Case): Path =
        exchangeStorageService
            .caseRoot(rootCase.namespaceId, rootCase.id, rootCase.metadata.created)
            .resolve("repo")
            .toAbsolutePath()
            .normalize()

    /**
     * Resolve the main branch once and persist it, so every later step and every retry agree on
     * the commit this case started from.
     */
    private fun freezeBaseSha(
        binding: CaseResourceBinding,
        settings: GitRepositorySettings,
        gitDir: Path,
    ): CaseResourceBinding {
        binding.baseSha?.let { return binding }

        runner.assertNoHostileLocalConfig(gitDir)
        val baseRef = "refs/agentos/base/${binding.rootCaseId}"
        val existing = runner.run(GitInvocation(listOf("rev-parse", "--verify", "$baseRef^{commit}"), gitDir = gitDir))
        if (existing is GitCommandResult.Completed && existing.successful) {
            return bindingService.update(binding.copy(baseSha = existing.stdout.trim()))
        }
        runner.runOrThrow(
            GitInvocation(
                args = listOf("fetch", "--quiet", settings.repositoryUrl, "refs/heads/${settings.mainBranch}:$baseRef"),
                gitDir = gitDir,
                timeout = gitProperties.cloneTimeout,
                credentials = serviceAccountResolver.resolve(settings),
            ),
        )
        val sha =
            runner.runOrThrow(
                GitInvocation(args = listOf("rev-parse", "--verify", "$baseRef^{commit}"), gitDir = gitDir),
            )

        return bindingService.update(binding.copy(baseSha = sha))
    }

    /**
     * A checkout interrupted before the workspace was ever ready (typically a redeploy during
     * `worktree add`) is refused by [isWorktree] on every retry. Nothing but Git wrote into it:
     * agents and setup only start once the checkout has completed. Keep its files for inspection
     * in workspace support storage, drop its registration, and let the checkout start over.
     */
    private fun setAsideInterruptedCheckout(
        binding: CaseResourceBinding,
        gitDir: Path,
        worktreePath: Path,
    ) {
        if (binding.setupStarted || binding.setupCompleted) return
        val registration =
            try {
                isWorktree(worktreePath, gitDir)
                return
            } catch (e: IncompleteWorktreeException) {
                e.registration
            }
        val support = Files.createDirectories(
            exchangeStorageService.workspaceSupportDirectory(binding.namespaceId, binding.rootCaseId),
        )
        val setAside = support.resolve("interrupted-checkout-${System.currentTimeMillis()}")
        Files.move(worktreePath, setAside)
        // Its HEAD is the frozen base, still referenced by refs/agentos/base/<root case id>.
        Files.walk(registration).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        logger.warn { "Interrupted checkout of root case ${binding.rootCaseId} set aside in $setAside" }
    }

    private fun addWorktree(
        binding: CaseResourceBinding,
        gitDir: Path,
        worktreePath: Path,
    ) {
        if (isWorktree(worktreePath, gitDir)) return

        // `git worktree add` refuses a target that exists and is not empty. An empty directory is
        // fine, and one is routinely pre-created by the exchange tool grant, so only a populated
        // one is a real problem — and it is one we must not resolve by deleting whatever is there.
        if (worktreePath.exists()) {
            check(worktreePath.isDirectory()) { "Cannot create a worktree at $worktreePath: a file is in the way" }
            val entries = worktreePath.listDirectoryEntries()
            check(entries.isEmpty()) {
                "Cannot create a worktree at $worktreePath: the directory already holds ${entries.size} entr(ies). " +
                    "Files must not be written there before the workspace is ready."
            }
        } else {
            Files.createDirectories(worktreePath.parent)
        }

        // Do not prune registrations here: another family's checkout may only be temporarily
        // unavailable. Git can allocate a new worktree without discarding its index or metadata.
        runner.runOrThrow(
            GitInvocation(
                args = listOf("worktree", "add", "--detach", worktreePath.toString(), requireNotNull(binding.baseSha)),
                gitDir = gitDir,
                timeout = gitProperties.cloneTimeout,
            ),
        )
        // Git chooses an administrative name from the leaf directory (now always "repo").
        // Pin it to the root case id so status/lifecycle never trust the writable pointer file.
        check(isWorktree(worktreePath, gitDir)) { "Git did not register the new worktree" }
        logger.info { "Worktree for root case ${binding.rootCaseId} created at $worktreePath at ${binding.baseSha} (detached)" }
    }

    /** A linked worktree carries a `.git` pointer file; the managed clone carries a directory. */
    private fun isWorktree(path: Path, commonGitDir: Path): Boolean {
        val pointer = path.resolve(GIT_DIR_NAME)
        if (!Files.exists(pointer, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false
        val registrations = commonGitDir.resolve("worktrees").toRealPath()
        val admin = registrations.resolve(path.parent.fileName)
        check(Files.isRegularFile(pointer, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(admin)) {
            "The case Exchange is not the registered workspace"
        }
        val contents = Files.readString(pointer).trim()
        check(contents.startsWith("gitdir: ")) { "The worktree has an invalid Git pointer" }
        val target = path.resolve(contents.removePrefix("gitdir: ")).normalize()
        check(target.parent.toRealPath() == registrations && !Files.isSymbolicLink(target)) {
            "The worktree points to another repository"
        }
        // A crash can leave Git's allocated name, or move it to the pinned name before updating
        // the pointer. Adopt only a registration that points back to this exact Exchange.
        val registration = when {
            Files.exists(target) -> target
            Files.isDirectory(admin) -> admin
            else -> error("The worktree registration is missing")
        }
        check(registration.resolve(Files.readString(registration.resolve("commondir")).trim()).normalize().toRealPath() == commonGitDir.toRealPath()) {
            "The worktree no longer belongs to the namespace repository"
        }
        check(registration.resolve(Files.readString(registration.resolve("gitdir")).trim()).normalize().toRealPath() == pointer.toRealPath()) {
            "The worktree registration points to another Exchange"
        }
        // HEAD and both pointers are written before checkout starts. They cannot prove that
        // worktree add finished: a crash may leave an initializing lock and no index at all.
        val lock = registration.resolve("locked")
        if (Files.exists(lock) && Files.readString(lock).trim() == "initializing") {
            throw IncompleteWorktreeException(
                registration,
                "Worktree checkout was interrupted during initialization; inspect and complete its recovery before retrying",
            )
        }
        if (!Files.isRegularFile(registration.resolve("index"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw IncompleteWorktreeException(
                registration,
                "Worktree checkout has no index; inspect and complete its recovery before retrying",
            )
        }
        if (registration != admin) Files.move(registration, admin)
        if (target != admin) Files.writeString(pointer, "gitdir: $admin\n")
        runner.runOrThrow(GitInvocation(listOf("rev-parse", "--verify", "HEAD^{commit}"), gitDir = admin))
        return true
    }

    /** Git registered the worktree but never completed its checkout. */
    private class IncompleteWorktreeException(
        val registration: Path,
        message: String,
    ) : IllegalStateException(message)

    companion object : KLogging() {
        private const val GIT_DIR_NAME = ".git"
    }
}
