package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.authSetting.AuthSettingService
import io.whozoss.agentos.authSetting.BearerTokenAuthSetting
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exchange.ExchangeStorageConfigProperties
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Real Git provisioning: internal bare storage, document isolation and retry preservation. */
class RepositoryCheckoutProvisionerSpec :
    StringSpec({
        timeout = 120_000

        val gitProperties = GitExecutionProperties(allowedRemoteProtocols = setOf("https", "file"))
        val runner = GitCommandRunner(gitProperties)

        fun rawGit(
            directory: Path,
            vararg args: String,
        ): String {
            val process =
                ProcessBuilder(listOf("git", *args))
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .also {
                        it.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                        it.environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
                    }.start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { output }
            return output.trim()
        }

        /** A throwaway origin repository containing README.md and src/index.ts. */
        fun originRepository(): Path {
            val root = Files.createTempDirectory("agentos-origin-")
            rawGit(root, "init", "--quiet", "--initial-branch=main")
            rawGit(root, "config", "user.email", "ci@example.com")
            rawGit(root, "config", "user.name", "CI")
            rawGit(root, "config", "commit.gpgsign", "false")
            root.resolve("README.md").writeText("from the repository\n")
            Files.createDirectories(root.resolve("src"))
            root.resolve("src/index.ts").writeText("export const x = 1\n")
            rawGit(root, "add", "-A")
            rawGit(root, "commit", "--quiet", "-m", "base")
            return root
        }

        fun fixture(): Triple<ExchangeStorageService, RepositoryCheckoutProvisioner, UUID> {
            val mount = Files.createTempDirectory("agentos-mount-")
            val storage = ExchangeStorageService(ExchangeStorageConfigProperties(mountRoot = mount.toString()))
            val namespaceId = UUID.randomUUID()

            // `findById` carries a default implementation on EntityService, but mockk intercepts it
            // directly rather than letting it delegate to findByIds — so it is the method to stub.
            val authSettings =
                mockk<AuthSettingService> {
                    every { findById(any(), any()) } returns
                        BearerTokenAuthSetting(
                            metadata = EntityMetadata(),
                            namespaceId = namespaceId,
                            userId = null,
                            name = "git-service-account",
                            token = "unused-for-file-transport",
                        )
                }

            val provisioner =
                RepositoryCheckoutProvisioner(
                    runner = runner,
                    gitProperties = gitProperties,
                    exchangeStorageService = storage,
                    checkoutService = InMemoryRepositoryCheckoutService(),
                    serviceAccountResolver = GitServiceAccountResolver(authSettings),
                )
            return Triple(storage, provisioner, namespaceId)
        }

        fun settings(
            namespaceId: UUID,
            origin: Path,
        ): GitRepositorySettings =
            GitRepositorySettings(
                configId = UUID.randomUUID(),
                namespaceId = namespaceId,
                repositoryUrl = origin.toUri().toString(),
                mainBranch = "main",
                serviceAuthSettingId = UUID.randomUUID(),
                autoWorktreeForRootCases = false,
                setupCommand = null,
            )

        "the bare repository lives outside both browsable Exchange roots" {
            val (storage, provisioner, namespaceId) = fixture()

            val checkout = provisioner.ensureReady(settings(namespaceId, originRepository()))

            checkout.status shouldBe RepositoryCheckoutStatus.READY
            val root = storage.namespaceRoot(namespaceId)
            val git = storage.namespaceGitDirectory(namespaceId)
            rawGit(git, "rev-parse", "--is-bare-repository") shouldBe "true"
            rawGit(git, "show", "HEAD:README.md") shouldBe "from the repository"
            rawGit(git, "config", "remote.origin.fetch") shouldBe "+refs/heads/*:refs/remotes/origin/*"
            rawGit(git, "rev-parse", "refs/remotes/origin/main") shouldBe rawGit(git, "rev-parse", "HEAD")
            git.resolve("README.md").exists() shouldBe false
            git.startsWith(root) shouldBe false
            root.exists() shouldBe false
        }

        "pre-existing namespace files remain outside the checkout" {
            val (storage, provisioner, namespaceId) = fixture()
            val root = storage.namespaceRoot(namespaceId)
            Files.createDirectories(root)
            root.resolve("spec.md").writeText("a spec written before the association\n")

            provisioner.ensureReady(settings(namespaceId, originRepository()))

            root.resolve("spec.md").readText() shouldBe "a spec written before the association\n"
            root.resolve("repo").exists() shouldBe false
        }

        "a pre-existing file identical to the repository's is not a conflict" {
            val (storage, provisioner, namespaceId) = fixture()
            val root = storage.namespaceRoot(namespaceId)
            Files.createDirectories(root)
            root.resolve("README.md").writeText("from the repository\n")

            provisioner.ensureReady(settings(namespaceId, originRepository())).status shouldBe RepositoryCheckoutStatus.READY
        }

        "namespace documents may have the same name as different repository files" {
            val (storage, provisioner, namespaceId) = fixture()
            val root = storage.namespaceRoot(namespaceId)
            Files.createDirectories(root)
            root.resolve("README.md").writeText("locally edited, not in the repository\n")

            provisioner.ensureReady(settings(namespaceId, originRepository())).status shouldBe RepositoryCheckoutStatus.READY
            root.resolve("repo").exists() shouldBe false
            root.resolve("README.md").readText() shouldBe "locally edited, not in the repository\n"
            root.resolve(".git").exists() shouldBe false
        }

        listOf(".git", "repo/.git").forEach { legacyPath ->
            "a legacy clone at $legacyPath is preserved and blocks a second repository" {
                val (storage, provisioner, namespaceId) = fixture()
                val legacy = storage.namespaceRoot(namespaceId).resolve(legacyPath)
                Files.createDirectories(legacy)
                legacy.resolve("sentinel").writeText("preserve")
                shouldThrow<IllegalStateException> { provisioner.ensureReady(settings(namespaceId, originRepository())) }
                    .message!! shouldContain "legacy namespace checkout"
                legacy.resolve("sentinel").readText() shouldBe "preserve"
                storage.namespaceGitDirectory(namespaceId).exists() shouldBe false
            }
        }

        "preparation is idempotent once ready" {
            val (storage, provisioner, namespaceId) = fixture()
            val configured = settings(namespaceId, originRepository())

            val first = provisioner.ensureReady(configured)
            val second = provisioner.ensureReady(configured)

            second.id shouldBe first.id
            rawGit(storage.namespaceGitDirectory(namespaceId), "rev-parse", "--is-bare-repository") shouldBe "true"
        }

        "a mount root configured relative to the working directory still works" {
            // `agentos.exchange.mount-root` defaults to `data/exchange/`, a path relative to the
            // JVM working directory — while git subprocesses run with a working directory of their
            // own, so the same relative string names two different places. Every other case here
            // uses createTempDirectory (absolute) and so never exercised the shipped default.
            val relativeMount = "build/test-exchange-${UUID.randomUUID()}"
            val storage = ExchangeStorageService(ExchangeStorageConfigProperties(mountRoot = relativeMount))
            val namespaceId = UUID.randomUUID()
            val authSettings =
                mockk<AuthSettingService> {
                    every { findById(any(), any()) } returns
                        BearerTokenAuthSetting(
                            metadata = EntityMetadata(),
                            namespaceId = namespaceId,
                            userId = null,
                            name = "git-service-account",
                            token = "unused-for-file-transport",
                        )
                }
            val provisioner =
                RepositoryCheckoutProvisioner(
                    runner = runner,
                    gitProperties = gitProperties,
                    exchangeStorageService = storage,
                    checkoutService = InMemoryRepositoryCheckouts(),
                    serviceAccountResolver = GitServiceAccountResolver(authSettings),
                )

            try {
                val checkout =
                    provisioner.ensureReady(
                        GitRepositorySettings(
                            configId = UUID.randomUUID(),
                            namespaceId = namespaceId,
                            repositoryUrl = originRepository().toUri().toString(),
                            mainBranch = "main",
                            serviceAuthSettingId = UUID.randomUUID(),
                            autoWorktreeForRootCases = false,
                            setupCommand = null,
                        ),
                    )

                checkout.status shouldBe RepositoryCheckoutStatus.READY
                rawGit(storage.namespaceGitDirectory(namespaceId), "show", "HEAD:README.md") shouldBe "from the repository"
            } finally {
                Path.of(relativeMount).toFile().deleteRecursively()
            }
        }

        "repointing an associated namespace at another repository is refused" {
            val (_, provisioner, namespaceId) = fixture()
            provisioner.ensureReady(settings(namespaceId, originRepository()))

            val error =
                shouldThrow<ConflictException> { provisioner.ensureReady(settings(namespaceId, originRepository())) }

            error.message!! shouldContain "explicit migration"
        }

        "requesting a preparation records the intent without cloning" {
            // Associating has to return immediately: a clone takes minutes and this runs in a
            // request thread. The row is the queue the sweep reads.
            val (storage, provisioner, namespaceId) = fixture()

            val requested = provisioner.requestPreparation(settings(namespaceId, originRepository()))

            requested.status shouldBe RepositoryCheckoutStatus.PREPARING
            storage.namespaceGitDirectory(namespaceId).exists() shouldBe false
        }

        "saving corrected settings explicitly requeues a failed checkout" {
            val (_, provisioner, namespaceId) = fixture()
            val origin = originRepository()
            val configured = settings(namespaceId, origin).copy(mainBranch = "later")
            shouldThrow<GitCommandException> { provisioner.ensureReady(configured) }
            rawGit(origin, "branch", "later")

            val requested = provisioner.requestPreparation(configured)
            requested.status shouldBe RepositoryCheckoutStatus.PREPARING
            requested.failureReason shouldBe null
            provisioner.ensureReady(configured).status shouldBe RepositoryCheckoutStatus.READY
        }

        "a requested checkout is cloned even though no case ever asks for a worktree" {
            val (storage, provisioner, namespaceId) = fixture()
            val configured = settings(namespaceId, originRepository())
            configured.autoWorktreeForRootCases shouldBe false
            provisioner.requestPreparation(configured)

            val ready = provisioner.ensureReady(configured)

            ready.status shouldBe RepositoryCheckoutStatus.READY
            rawGit(storage.namespaceGitDirectory(namespaceId), "show", "HEAD:README.md") shouldBe "from the repository"
        }

        "a checkout already on disk is marked ready rather than cloned again" {
            // A crash between publishing the tree and recording READY left the row PREPARING.
            // Re-running the whole clone would move the published tree aside into another dated
            // backup on every pass.
            val (storage, provisioner, namespaceId) = fixture()
            val configured = settings(namespaceId, originRepository())
            provisioner.ensureReady(configured)
            val root = storage.namespaceRoot(namespaceId)
            Files.createDirectories(root)
            root.resolve("untracked-work.txt").writeText("written after the clone\n")

            val again = provisioner.ensureReady(configured)

            again.status shouldBe RepositoryCheckoutStatus.READY
            root.resolve("untracked-work.txt").exists() shouldBe true
            root.parent.listDirectoryEntries("*.pre-git-*").shouldBeEmpty()
        }

        "re-associating the same repository adopts the existing checkout" {
            // Removing an association deletes its config row, and creating it again mints a new id.
            // `matches` compares that id, so the same repository associated twice used to leave a
            // checkout no configuration could match, and every later provisioning threw the
            // permanent conflict above with no way out.
            val (storage, provisioner, namespaceId) = fixture()
            val origin = originRepository()
            val first = provisioner.ensureReady(settings(namespaceId, origin))

            val reassociated = settings(namespaceId, origin)
            val second = provisioner.ensureReady(reassociated)

            second.id shouldBe first.id
            second.integrationConfigId shouldBe reassociated.configId
            second.status shouldBe RepositoryCheckoutStatus.READY
            rawGit(storage.namespaceGitDirectory(namespaceId), "rev-parse", "--is-bare-repository") shouldBe "true"
        }
    })

/** Minimal in-memory [RepositoryCheckoutService] for the provisioning tests. */
private class InMemoryRepositoryCheckoutService : RepositoryCheckoutService {
    private val rows = mutableMapOf<UUID, RepositoryCheckout>()

    override fun create(entity: RepositoryCheckout): RepositoryCheckout = entity.also { rows[it.id] = it }

    override fun update(entity: RepositoryCheckout): RepositoryCheckout = entity.also { rows[it.id] = it }

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<RepositoryCheckout> = ids.mapNotNull { rows[it] }.filter { withRemoved || !it.metadata.removed }

    override fun findByParent(parentId: UUID): List<RepositoryCheckout> = rows.values.filter { it.namespaceId == parentId }

    override fun findByStatusIn(
        statuses: Collection<RepositoryCheckoutStatus>,
        limit: Int,
    ): List<RepositoryCheckout> = rows.values.filter { it.status in statuses }.take(limit)

    override fun findByNamespaceId(namespaceId: UUID): RepositoryCheckout? =
        rows.values.firstOrNull { it.namespaceId == namespaceId && !it.metadata.removed }

    override fun delete(id: UUID): Boolean =
        rows[id]?.let { rows[id] = it.copy(metadata = it.metadata.copy(removed = true)); true } ?: false

    override fun deleteByParent(parentId: UUID): Int = findByParent(parentId).count { delete(it.id) }

    override fun markStatus(
        id: UUID,
        status: RepositoryCheckoutStatus,
        failureReason: String?,
    ): RepositoryCheckout {
        val current = requireNotNull(rows[id]) { "checkout $id not found" }
        return current.copy(status = status, failureReason = failureReason).also { rows[id] = it }
    }
}
