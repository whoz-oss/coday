package io.whozoss.agentos.git

import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.git.core.GitCommandException
import io.whozoss.agentos.git.core.GitCommandResult
import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitExecutionProperties
import io.whozoss.agentos.git.core.GitInvocation
import mu.KLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.name

/** Prepares an internal bare repository outside the browsable Exchanges. */
@Service
class RepositoryCheckoutProvisioner(
    private val runner: GitCommandRunner,
    private val gitProperties: GitExecutionProperties,
    private val exchangeStorageService: ExchangeStorageService,
    private val checkoutService: RepositoryCheckoutService,
    private val serviceAccountResolver: GitServiceAccountResolver,
    private val bindingService: CaseResourceBindingService? = null,
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
        val existing = checkoutService.findByNamespaceId(settings.namespaceId)?.let { adoptIfSameRepository(it, settings) }
        if (existing != null && !existing.matches(settings)) {
            throw ConflictException(
                "Namespace ${settings.namespaceId} already has a checkout of ${existing.repositoryUrl} " +
                    "(branch ${existing.mainBranch}). Pointing it at ${settings.repositoryUrl} requires an " +
                    "explicit migration: the existing checkout, and any work in it, would otherwise be orphaned.",
            )
        }
        // A failed clone is retried only when the settings are saved again (requestPreparation).
        // Re-cloning here would restart it for every new root case and keep the worker busy.
        if (existing?.status == RepositoryCheckoutStatus.FAILED && !Files.exists(namespaceRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw ConflictException(
                "The namespace repository could not be prepared. Save the namespace Git settings to retry.",
            )
        }

        val checkout = existing ?: checkoutService.create(newCheckout(settings))
        var failureReason = "Cannot access the namespace repository files."
        return try {
            val sharedRoot = exchangeStorageService.namespaceRoot(settings.namespaceId)
            failureReason = "A legacy namespace checkout exists. Move it to internal storage before provisioning."
            check(!Files.exists(sharedRoot.resolve(".git")) && !Files.exists(sharedRoot.resolve("repo/.git"))) {
                "A legacy namespace checkout exists. Move it to internal storage before provisioning."
            }
            failureReason = "Repository preparation failed. Check repository access, the main branch, service account and local Git configuration."
            // Publishing may have succeeded just before a crash. Never clone over that repository.
            if (isGitRepository(namespaceRoot)) {
                when (checkout.status) {
                    RepositoryCheckoutStatus.READY -> checkout
                    else -> checkoutService.markStatus(checkout.id, RepositoryCheckoutStatus.READY)
                }
            } else {
                prepare(settings, checkout, namespaceRoot)
            }
        } catch (e: Exception) {
            logger.error { "Namespace ${settings.namespaceId}: $failureReason (${e.javaClass.simpleName})" }
            checkoutService.markStatus(
                checkout.id,
                RepositoryCheckoutStatus.FAILED,
                failureReason = failureReason,
            )
            throw e
        }
    }

    /**
     * Record that a namespace's checkout has to be prepared, without preparing it.
     *
     * Associating a repository has to return immediately: a clone takes minutes and a request thread
     * is the wrong place for it. This writes the intent, and [CaseWorkspaceWorker] picks it up — a queued state which survives process restarts.
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

    /** A failed first attempt is replaceable only while no repository or active family can be orphaned. */
    fun canReplaceFailedCheckout(checkout: RepositoryCheckout): Boolean =
        checkout.status == RepositoryCheckoutStatus.FAILED &&
            checkout.lastFetchedAt == null &&
            !Files.exists(exchangeStorageService.namespaceGitDirectory(checkout.namespaceId), NOFOLLOW_LINKS) &&
            bindingService?.findByParent(checkout.namespaceId)?.all {
                // Deleted, never-provisioned families retain their audit rows. They must not
                // permanently prevent an admin from correcting an unused failed association.
                it.status == CaseResourceStatus.REMOVED && it.baseSha == null && !it.setupStarted && !it.setupCompleted
            } == true

    /**
     * Re-point a checkout of the *same* repository at a re-created association.
     *
     * [RepositoryCheckout.matches] compares the association id as well as the URL and branch, which
     * is right for deciding whether a clone on disk still serves the configuration. But removing an
     * association deletes its config row, and creating it again mints a new id — so associating the
     * very same repository twice left a checkout no configuration could ever match, and every later
     * provisioning threw the permanent conflict below with no way out.
     *
     * Existing resources require identical URL and branch. A failed first attempt may change them
     * only when [canReplaceFailedCheckout] proves there is no published repository or bound family.
     */
    private fun adoptIfSameRepository(
        existing: RepositoryCheckout,
        settings: GitRepositorySettings,
    ): RepositoryCheckout {
        val sameRepository =
            existing.repositoryUrl == settings.repositoryUrl && existing.mainBranch == settings.mainBranch
        if (!sameRepository && canReplaceFailedCheckout(existing)) {
            return checkoutService.update(
                existing.copy(
                    integrationConfigId = settings.configId,
                    repositoryUrl = settings.repositoryUrl,
                    mainBranch = settings.mainBranch,
                    status = RepositoryCheckoutStatus.PREPARING,
                    failureReason = null,
                ),
            )
        }
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
        // Bare clone imports every branch as a local head. Move those initial snapshots to origin/*
        // before publication, so `git switch <remote-branch>` can create a fresh tracking branch.
        // Once published, local heads belong to agents and are never reset by the service.
        runner.runOrThrow(GitInvocation(listOf("config", "remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/*"), gitDir = staging))
        val head = runner.runOrThrow(GitInvocation(listOf("rev-parse", "HEAD"), gitDir = staging))
        runner.runOrThrow(GitInvocation(listOf("update-ref", "--no-deref", "HEAD", head), gitDir = staging))
        // Removing each processed head makes the next page advance without an unbounded listing.
        // The clone is still private staging: these refs cannot belong to a running agent.
        var pageSize = 64
        while (true) {
            val result = runner.run(
                GitInvocation(listOf("for-each-ref", "--count=$pageSize", "--format=%(objectname) %(refname)", "refs/heads/"), gitDir = staging),
            )
            if (result is GitCommandResult.Completed && result.truncated && pageSize > 1) {
                pageSize /= 2
                continue
            }
            if (result !is GitCommandResult.Completed || !result.successful || result.truncated) {
                throw GitCommandException("Cannot inspect the initial branch references completely")
            }
            val branches = result.stdout.trim()
            if (branches.isBlank()) break
            branches.lineSequence().forEach { line ->
                val (sha, ref) = line.split(' ', limit = 2)
                val remoteRef = "refs/remotes/origin/${ref.removePrefix("refs/heads/")}"
                runner.runOrThrow(GitInvocation(listOf("update-ref", remoteRef, sha), gitDir = staging))
                runner.runOrThrow(GitInvocation(listOf("update-ref", "-d", ref, sha), gitDir = staging))
            }
        }
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

        val head = runner.runOrThrow(GitInvocation(listOf("rev-parse", "HEAD"), gitDir = gitDir))
        val configured = runner.runOrThrow(
            GitInvocation(listOf("rev-parse", "--verify", "refs/remotes/origin/${settings.mainBranch}^{commit}"), gitDir = gitDir),
        )
        check(head == configured) { "Clone HEAD does not match the configured main branch" }
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
    }
}
