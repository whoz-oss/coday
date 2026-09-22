package io.whozoss.agentos.git

import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.exchange.ExchangeStorageService
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
        val worktreePath = worktreePath(rootCase)
        val commonGitDir = exchangeStorageService.namespaceGitDirectory(settings.namespaceId).toAbsolutePath().normalize()
        return try {
            if (binding.status == CaseResourceStatus.READY && isWorktree(worktreePath, commonGitDir)) return binding
            bindingService.markStatus(binding.id, CaseResourceStatus.PREPARING)
            val checkout = checkoutProvisioner.ensureReady(settings)
            check(checkout.status == RepositoryCheckoutStatus.READY) {
                "The namespace checkout is ${checkout.status}; a case workspace cannot be derived from it yet"
            }
            val gitDir =
                exchangeStorageService
                    .namespaceGitDirectory(settings.namespaceId)
                    .toAbsolutePath()
                    .normalize()

            val withBase = freezeBaseSha(binding, settings, gitDir)
            addWorktree(withBase, gitDir, worktreePath)
            val current = bindingService.findByRootCaseId(binding.rootCaseId) ?: withBase
            if (!current.setupCompleted) {
                check(!current.setupStarted) { "Setup was interrupted; acknowledge its replay before retrying" }
                bindingService.update(current.copy(setupStarted = true))
                setupRunner.run(settings, worktreePath)
                val afterSetup = bindingService.findByRootCaseId(binding.rootCaseId) ?: current
                bindingService.update(afterSetup.copy(setupCompleted = true))
            }

            bindingService.markStatus(withBase.id, CaseResourceStatus.READY)
        } catch (e: Exception) {
            logger.error(e) { "Workspace preparation failed for root case ${binding.rootCaseId}" }
            bindingService.markStatus(
                binding.id,
                CaseResourceStatus.FAILED,
                failureReason = e.message?.take(MAX_FAILURE_REASON_LENGTH),
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

        // A worktree whose directory vanished -- a failed attempt, a crash mid-preparation, a
        // wiped volume -- stays registered in the repository's administrative data, and `add` then
        // refuses the same path as "missing but already registered". Pruning clears exactly those
        // stale entries and never touches a worktree that still exists on disk, which is what
        // makes a retry able to recreate this one.
        runner.runOrThrow(GitInvocation(args = listOf("worktree", "prune"), gitDir = gitDir))

        runner.runOrThrow(
            GitInvocation(
                args = listOf("worktree", "add", "--detach", worktreePath.toString(), requireNotNull(binding.baseSha)),
                gitDir = gitDir,
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
        check(Path.of(Files.readString(registration.resolve("gitdir")).trim()).toRealPath() == pointer.toRealPath()) {
            "The worktree registration points to another Exchange"
        }
        if (registration != admin) Files.move(registration, admin)
        if (target != admin) Files.writeString(pointer, "gitdir: $admin\n")
        runner.runOrThrow(GitInvocation(listOf("rev-parse", "--verify", "HEAD^{commit}"), gitDir = admin))
        return true
    }

    companion object : KLogging() {
        private const val GIT_DIR_NAME = ".git"
        private const val MAX_FAILURE_REASON_LENGTH = 2_000
    }
}
