package io.whozoss.agentos.git

import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exchange.ExchangeStorageService
import mu.KLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.name

/** Prepares an internal bare repository. Exchanges only expose documents and case worktrees. */
@Service
class RepositoryCheckoutProvisioner(
    private val runner: GitCommandRunner,
    private val gitProperties: GitExecutionProperties,
    private val exchangeStorageService: ExchangeStorageService,
    private val checkoutService: RepositoryCheckoutService,
    private val serviceAccountResolver: GitServiceAccountResolver,
) {
    /**
     * Return the namespace's ready checkout, preparing it if needed.
     *
     * @throws ConflictException when a checkout exists for a different association — changing the
     *   repository URL or main branch of an associated namespace is an explicit migration, not a
     *   side effect of saving a configuration.
     */
    fun ensureReady(settings: GitRepositorySettings): RepositoryCheckout {
        // Absolute from here on. `agentos.exchange.mount-root` defaults to a path relative to the
        // JVM working directory, and git subprocesses run with a working directory of their own,
        // so a relative path would name a different place in the parent and in the child.
        val namespaceRoot = exchangeStorageService.namespaceGitDirectory(settings.namespaceId).toAbsolutePath().normalize()
        val sharedRoot = exchangeStorageService.namespaceRoot(settings.namespaceId)
        check(!Files.exists(sharedRoot.resolve(".git")) && !Files.exists(sharedRoot.resolve("repo/.git"))) {
            "A legacy namespace checkout exists. Move it to internal storage before provisioning."
        }
        val existing = checkoutService.findByNamespaceId(settings.namespaceId)?.let { adoptIfSameRepository(it, settings) }

        if (existing != null && !existing.matches(settings)) {
            throw ConflictException(
                "Namespace ${settings.namespaceId} already has a checkout of ${existing.repositoryUrl} " +
                    "(branch ${existing.mainBranch}). Pointing it at ${settings.repositoryUrl} requires an " +
                    "explicit migration: the existing checkout, and any work in it, would otherwise be orphaned.",
            )
        }

        // A namespace root that is already a clone is done, whatever the row says. The recorded
        // status can lag reality: a crash between `publish` and marking READY used to leave the row
        // PREPARING, and re-running the whole clone would then move the published tree aside into
        // another dated backup on every pass.
        if (existing != null && isGitRepository(namespaceRoot)) {
            return when (existing.status) {
                RepositoryCheckoutStatus.READY -> existing
                else -> checkoutService.markStatus(existing.id, RepositoryCheckoutStatus.READY)
            }
        }

        val checkout = existing ?: checkoutService.create(newCheckout(settings))
        return prepare(settings, checkout, namespaceRoot)
    }

    /**
     * Record that a namespace's checkout has to be prepared, without preparing it.
     *
     * Associating a repository has to return immediately: a clone takes minutes and a request thread
     * is the wrong place for it. This writes the intent, and [CaseWorkspaceWorker] picks it up — the
     * same shape as a case workspace, where creation records `REQUESTED` and the sweep does the work.
     *
     * Saving the namespace settings explicitly retries a failed checkout. The worker only picks
     * up PREPARING rows, so a misconfigured repository is never retried on every timer tick.
     */
    fun requestPreparation(settings: GitRepositorySettings): RepositoryCheckout {
        val existing = checkoutService.findByNamespaceId(settings.namespaceId)?.let { adoptIfSameRepository(it, settings) }
        if (existing != null) {
            check(existing.matches(settings)) { "The checkout belongs to another repository or main branch" }
            return when (existing.status) {
                RepositoryCheckoutStatus.FAILED -> checkoutService.markStatus(existing.id, RepositoryCheckoutStatus.PREPARING)
                else -> existing
            }
        }

        logger.info { "Namespace ${settings.namespaceId} requested a checkout of ${settings.repositoryUrl}" }
        return checkoutService.create(newCheckout(settings))
    }

    /**
     * Re-point a checkout of the *same* repository at a re-created association.
     *
     * [RepositoryCheckout.matches] compares the association id as well as the URL and branch, which
     * is right for deciding whether a clone on disk still serves the configuration. But removing an
     * association deletes its config row, and creating it again mints a new id — so associating the
     * very same repository twice left a checkout no configuration could ever match, and every later
     * provisioning threw the permanent conflict below with no way out.
     *
     * Adoption is only allowed when the URL and the branch are identical, which means the clone on
     * disk is exactly what the new association asks for. Anything else still conflicts: that is the
     * case where work would be orphaned, and it needs a real migration.
     */
    private fun adoptIfSameRepository(
        existing: RepositoryCheckout,
        settings: GitRepositorySettings,
    ): RepositoryCheckout {
        val sameRepository =
            existing.repositoryUrl == settings.repositoryUrl && existing.mainBranch == settings.mainBranch
        if (existing.integrationConfigId == settings.configId || !sameRepository) return existing

        logger.info {
            "Adopting the existing checkout of ${existing.repositoryUrl} in namespace " +
                "${settings.namespaceId} under the re-created association ${settings.configId}"
        }
        return checkoutService.update(existing.copy(integrationConfigId = settings.configId))
    }

    private fun newCheckout(settings: GitRepositorySettings): RepositoryCheckout =
        RepositoryCheckout(
            namespaceId = settings.namespaceId,
            integrationConfigId = settings.configId,
            repositoryUrl = settings.repositoryUrl,
            mainBranch = settings.mainBranch,
            status = RepositoryCheckoutStatus.PREPARING,
        )

    private fun prepare(
        settings: GitRepositorySettings,
        checkout: RepositoryCheckout,
        namespaceRoot: Path,
    ): RepositoryCheckout {
        val staging = stagingDirectory(namespaceRoot)
        checkoutService.markStatus(checkout.id, RepositoryCheckoutStatus.PREPARING)

        return try {
            deleteTree(staging)
            Files.createDirectories(staging.parent)

            clone(settings, staging)
            verify(settings, staging)
            check(!namespaceRoot.exists() || Files.list(namespaceRoot).use { !it.findAny().isPresent }) {
                "The internal repository directory already contains files"
            }
            publish(namespaceRoot, staging)

            checkoutService.markStatus(checkout.id, RepositoryCheckoutStatus.READY)
        } catch (e: Exception) {
            // Keep the staging tree out of the way; the namespace's own files were never touched
            // unless `publish` completed, and `publish` is the last step.
            runCatching { deleteTree(staging) }
            logger.error(e) { "Preparation failed for namespace ${settings.namespaceId}" }
            checkoutService.markStatus(
                checkout.id,
                RepositoryCheckoutStatus.FAILED,
                failureReason = e.message?.take(MAX_FAILURE_REASON_LENGTH),
            )
            throw e
        }
    }

    private fun clone(
        settings: GitRepositorySettings,
        staging: Path,
    ) {
        runner.runOrThrow(
            GitInvocation(
                args =
                    listOf(
                        "clone",
                        "--bare",
                        "--branch",
                        settings.mainBranch,
                        "--",
                        settings.repositoryUrl,
                        staging.toString(),
                    ),
                workingDirectory = staging.parent,
                timeout = gitProperties.cloneTimeout,
                credentials = serviceAccountResolver.resolve(settings),
            ),
        )
        // A bare clone has no tracking refspec by default. Agent fetch/pull commands must update
        // origin/* rather than the local branches shared by the case worktrees.
        runner.runOrThrow(GitInvocation(listOf("config", "remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/*"), gitDir = staging))
        runner.runOrThrow(GitInvocation(listOf("update-ref", "refs/remotes/origin/${settings.mainBranch}", "HEAD"), gitDir = staging))
    }

    /**
     * Confirm the clone is the repository we asked for before anything is published, and that its
     * local configuration carries nothing able to hijack a later command.
     */
    private fun verify(
        settings: GitRepositorySettings,
        staging: Path,
    ) {
        check(isGitRepository(staging)) { "Clone did not produce a bare Git repository at $staging" }
        val gitDir = staging
        runner.assertNoHostileLocalConfig(gitDir)

        val branch =
            runner.runOrThrow(
                GitInvocation(args = listOf("rev-parse", "--abbrev-ref", "HEAD"), gitDir = gitDir),
            )
        if (branch != settings.mainBranch) {
            throw IllegalStateException("Clone is on branch '$branch' but '${settings.mainBranch}' was configured")
        }
    }

    /**
     * Swap the prepared clone in, keeping the previous directory as a dated backup rather than
     * deleting it. The backup is the only copy of anything the adoption step could not classify,
     * so it is retained until an operator removes it.
     */
    private fun publish(
        namespaceRoot: Path,
        staging: Path,
    ) {
        if (namespaceRoot.exists()) {
            val backup = namespaceRoot.resolveSibling("${namespaceRoot.name}$BACKUP_SUFFIX${Instant.now().toEpochMilli()}")
            Files.move(namespaceRoot, backup)
            logger.info { "Previous internal repository directory kept at $backup" }
        }
        Files.createDirectories(namespaceRoot.parent)
        Files.move(staging, namespaceRoot)
    }

    private fun stagingDirectory(namespaceRoot: Path): Path = namespaceRoot.resolveSibling(STAGING_DIR_NAME)

    private fun isGitRepository(root: Path): Boolean {
        if (!Files.isRegularFile(root.resolve("HEAD")) || !Files.isDirectory(root.resolve("objects"))) return false
        runner.assertNoHostileLocalConfig(root)
        return runner.runOrThrow(GitInvocation(listOf("rev-parse", "--is-bare-repository"), gitDir = root)) == "true"
    }

    private fun deleteTree(path: Path) {
        if (path.exists()) path.toFile().deleteRecursively()
    }

    companion object : KLogging() {
        /** Sibling of the namespace root, so the publish step is a rename on the same volume. */
        private const val STAGING_DIR_NAME = ".repository-staging"
        private const val BACKUP_SUFFIX = ".pre-git-"
        private const val MAX_FAILURE_REASON_LENGTH = 2_000
    }
}
