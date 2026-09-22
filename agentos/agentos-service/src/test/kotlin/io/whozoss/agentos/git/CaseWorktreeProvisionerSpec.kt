package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.authSetting.AuthSettingService
import io.whozoss.agentos.authSetting.BearerTokenAuthSetting
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.exchange.ExchangeStorageConfigProperties
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A case family gets its own detached worktree, cut from a frozen base commit.
 *
 * Exercised against a real repository: the retry cases are the point, since the design's whole
 * recovery story rests on preparation being safely repeatable.
 */
class CaseWorktreeProvisionerSpec :
    StringSpec({
        timeout = 180_000

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
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor(60, TimeUnit.SECONDS)
            return out
        }

        fun originRepository(): Path {
            val root = Files.createTempDirectory("agentos-origin-")
            rawGit(root, "init", "--quiet", "--initial-branch=main")
            rawGit(root, "config", "user.email", "ci@example.com")
            rawGit(root, "config", "user.name", "CI")
            rawGit(root, "config", "commit.gpgsign", "false")
            root.resolve("README.md").writeText("v1\n")
            rawGit(root, "add", "-A")
            rawGit(root, "commit", "--quiet", "-m", "base")
            return root
        }

        fun advanceOrigin(origin: Path) {
            origin.resolve("README.md").writeText("v2\n")
            rawGit(origin, "add", "-A")
            rawGit(origin, "commit", "--quiet", "-m", "advance")
        }

        class Fixture(
            val storage: ExchangeStorageService,
            val provisioner: CaseWorktreeProvisioner,
            val bindings: InMemoryCaseResourceBindingService,
            val namespaceId: UUID,
        )

        fun fixture(): Fixture {
            val mount = Files.createTempDirectory("agentos-mount-")
            val storage = ExchangeStorageService(ExchangeStorageConfigProperties(mountRoot = mount.toString()))
            val namespaceId = UUID.randomUUID()
            val bindings = InMemoryCaseResourceBindingService()

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
            val serviceAccounts = GitServiceAccountResolver(authSettings)

            val provisioner =
                CaseWorktreeProvisioner(
                    runner = runner,
                    gitProperties = gitProperties,
                    exchangeStorageService = storage,
                    bindingService = bindings,
                    checkoutProvisioner =
                        RepositoryCheckoutProvisioner(
                            runner = runner,
                            gitProperties = gitProperties,
                            exchangeStorageService = storage,
                            checkoutService = InMemoryRepositoryCheckouts(),
                            serviceAccountResolver = serviceAccounts,
                        ),
                    serviceAccountResolver = serviceAccounts,
                    setupRunner = WorktreeSetupRunner(gitProperties),
                )
            return Fixture(storage, provisioner, bindings, namespaceId)
        }

        fun settings(
            namespaceId: UUID,
            origin: Path,
            setupCommand: String? = null,
        ): GitRepositorySettings =
            GitRepositorySettings(
                configId = UUID.randomUUID(),
                namespaceId = namespaceId,
                repositoryUrl = origin.toUri().toString(),
                mainBranch = "main",
                serviceAuthSettingId = UUID.randomUUID(),
                autoWorktreeForRootCases = true,
                setupCommand = setupCommand,
            )

        fun rootCase(
            namespaceId: UUID,
            title: String,
        ): Case = Case(metadata = EntityMetadata(), namespaceId = namespaceId, title = title)

        fun binding(
            fixture: Fixture,
            rootCase: Case,
            configured: GitRepositorySettings,
        ): CaseResourceBinding =
            fixture.bindings.create(
                CaseResourceBinding(
                    rootCaseId = rootCase.id,
                    namespaceId = fixture.namespaceId,
                    integrationConfigId = configured.configId,
                ),
            )

        fun statusService(f: Fixture, settings: GitRepositorySettings, hosting: GitHostingProvider) = GitWorkspaceStatusService(
            f.bindings,
            mockk { every { findSettings(any()) } returns settings },
            f.storage, runner,
            mockk { every { resolve(any()) } returns GitCredentials.UsernamePassword("test", "unused") },
            hosting, com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().findAndRegisterModules(),
        )

        "status follows the branch created by the agent and clears when detached again" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Arbitrary title")
            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val hosting = mockk<GitHostingProvider> { every { inspect(any(), any(), any()) } returns GitWorkspaceSummary(prState = "NONE") }
            val status = statusService(f, configured, hosting)
            val path = f.provisioner.worktreePath(root)
            val detached = status.refresh(ready, path)
            detached.branchName shouldBe null
            status.summary(detached)!!.branchState shouldBe "DETACHED"
            io.mockk.verify(exactly = 0) { hosting.inspect(any(), any(), any()) }
            rawGit(path, "switch", "-c", "workflow/my-branch")
            val local = status.refresh(detached, path)
            local.branchName shouldBe "workflow/my-branch"
            status.summary(local)!!.branchState shouldBe "LOCAL_ONLY"
            io.mockk.verify { hosting.inspect(configured, "workflow/my-branch", null) }
            rawGit(path, "push", "origin", "workflow/my-branch")
            val pushed = status.refresh(local, path)
            status.summary(pushed)!!.branchState shouldBe "PUSHED"
            every { hosting.inspect(any(), any(), any()) } returns GitWorkspaceSummary(prState = "OPEN", prNumber = 42)
            status.summary(status.refresh(pushed, path))!!.prState shouldBe "OPEN"
            rawGit(path, "switch", "--detach", "HEAD")
            val cleared = status.refresh(pushed, path)
            cleared.branchName shouldBe null
            status.summary(cleared)!!.prNumber shouldBe null
        }

        "a PR checkout alias passes its actual HEAD to the hosting provider and clears on another checkout" {
            val f = fixture()
            val origin = originRepository()
            val configured = settings(f.namespaceId, origin)
            val root = rootCase(f.namespaceId, "Review an existing PR")
            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            // The agent fetches an existing PR into an arbitrary local branch without an upstream.
            rawGit(origin, "switch", "-c", "feature/source-branch")
            advanceOrigin(origin)
            val prHead = rawGit(origin, "rev-parse", "HEAD").trim()
            val path = f.provisioner.worktreePath(root)
            rawGit(path, "fetch", "origin", "feature/source-branch:pr-1301")
            rawGit(path, "switch", "pr-1301")
            val hosting = mockk<GitHostingProvider> {
                every { inspect(configured, "pr-1301", prHead) } returns
                    GitWorkspaceSummary(prState = "OPEN", prNumber = 1301, prHeadSha = prHead)
                every { inspect(configured, "another-task", null) } returns GitWorkspaceSummary(prState = "NONE")
            }
            val status = statusService(f, configured, hosting)
            val observed = status.refresh(ready, path)
            observed.branchName shouldBe "pr-1301"
            status.summary(observed)!!.let {
                it.error shouldBe null
                it.headSha shouldBe prHead
                it.prNumber shouldBe 1301
                it.prState shouldBe "OPEN"
            }
            io.mockk.verify(exactly = 1) { hosting.inspect(configured, "pr-1301", prHead) }
            // The previous PR must not stick to the case after the agent switches elsewhere.
            rawGit(path, "switch", "-c", "another-task", ready.baseSha!!)
            val switched = status.refresh(observed, path)
            switched.branchName shouldBe "another-task"
            status.summary(switched)!!.prState shouldBe "NONE"
            status.summary(switched)!!.prNumber shouldBe null
        }

        listOf("OPEN", "NONE", "UNKNOWN", "MERGED", "CLOSED_UNMERGED").forEach { prState ->
            "only case deletion triggers cleanup, independently of PR state $prState" {
                val f = fixture()
                val configured = settings(f.namespaceId, originRepository())
                var root = rootCase(f.namespaceId, "Delete")
                val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
                val path = f.provisioner.worktreePath(root)
                rawGit(path, "switch", "-c", "workflow/keep-branch")
                f.bindings.update(ready.copy(summaryJson = "{\"prState\":\"$prState\"}"))
                val hosting = mockk<GitHostingProvider>() // Cleanup must never call the hosting provider.
                val cases = mockk<io.whozoss.agentos.caseFlow.CaseRepository> {
                    every { findByIds(any(), any()) } answers { listOf(root) }
                    every { findIncludingRemovedByNamespace(any()) } answers { listOf(root) }
                }
                val roots = ExchangeRootResolver(cases, f.bindings, f.storage)
                val runtime = mockk<io.whozoss.agentos.caseFlow.CaseService> { every { hasRunningExecutions(any()) } returns false }
                path.parent.resolve("attachment.txt").writeText("Keep this document")
                val lifecycle = GitWorkspaceLifecycleService(f.bindings, cases,
                    mockk { every { getObject() } returns runtime }, roots, f.storage, runner,
                    mockk { every { findPlugin(any()) } returns null })
                // Neither a merged PR nor a killed/completed execution removes an existing case.
                root = root.copy(status = io.whozoss.agentos.sdk.caseFlow.CaseStatus.KILLED)
                lifecycle.cleanupDeletedCases()
                f.bindings.findByRootCaseId(root.id)!!.status shouldBe CaseResourceStatus.READY
                path.exists() shouldBe true
                root = root.copy(metadata = root.metadata.copy(removed = true))
                lifecycle.cleanupDeletedCases()
                f.bindings.findByRootCaseId(root.id)!!.status shouldBe CaseResourceStatus.REMOVED
                path.exists() shouldBe false
                path.parent.resolve("attachment.txt").readText() shouldBe "Keep this document"
                rawGit(f.storage.namespaceGitDirectory(f.namespaceId), "rev-parse", "refs/heads/workflow/keep-branch").trim() shouldBe ready.baseSha
                lifecycle.cleanupDeleted(root.id).status shouldBe CaseResourceStatus.REMOVED
                io.mockk.verify(exactly = 0) { cases.save(any()) }
                io.mockk.verify(exactly = 0) { hosting.inspect(any(), any(), any()) }
            }
        }

        "surviving descendants keep the shared worktree after deletion of their root" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            var root = rootCase(f.namespaceId, "Root")
            var child = rootCase(f.namespaceId, "Child").copy(parentCaseId = root.id)
            f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            val cases = mockk<io.whozoss.agentos.caseFlow.CaseRepository> {
                every { findByIds(any(), any()) } answers { listOf(root, child).filter { it.id in firstArg<Collection<UUID>>() } }
                every { findIncludingRemovedByNamespace(any()) } answers { listOf(root, child) }
            }
            val roots = ExchangeRootResolver(cases, f.bindings, f.storage)
            var running = false
            val runtime = mockk<io.whozoss.agentos.caseFlow.CaseService> { every { hasRunningExecutions(any()) } answers { running } }
            val lifecycle = GitWorkspaceLifecycleService(f.bindings, cases,
                mockk { every { getObject() } returns runtime }, roots, f.storage, runner,
                mockk { every { findPlugin(any()) } returns null })
            root = root.copy(metadata = root.metadata.copy(removed = true))
            lifecycle.cleanupDeleted(root.id).status shouldBe CaseResourceStatus.READY
            roots.resolve(child).requireRepository() shouldBe path
            child = child.copy(metadata = child.metadata.copy(removed = true))
            running = true
            lifecycle.cleanupDeleted(root.id).status shouldBe CaseResourceStatus.DELETING
            path.exists() shouldBe true
            running = false
            lifecycle.cleanupDeleted(root.id).status shouldBe CaseResourceStatus.REMOVED
            path.exists() shouldBe false
        }

        "dirty deleted worktrees are retained and cleanup can retry without changing cases" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            var root = rootCase(f.namespaceId, "Local work")
            f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            path.resolve("local.txt").writeText("Unsaved work")
            val cases = mockk<io.whozoss.agentos.caseFlow.CaseRepository> {
                every { findByIds(any(), any()) } answers { listOf(root) }
                every { findIncludingRemovedByNamespace(any()) } answers { listOf(root) }
            }
            val lifecycle = GitWorkspaceLifecycleService(f.bindings, cases,
                mockk { every { getObject() } returns mockk { every { hasRunningExecutions(any()) } returns false } },
                ExchangeRootResolver(cases, f.bindings, f.storage), f.storage, runner,
                mockk { every { findPlugin(any()) } returns null })
            root = root.copy(metadata = root.metadata.copy(removed = true))
            val retained = lifecycle.cleanupDeleted(root.id)
            retained.status shouldBe CaseResourceStatus.DELETING
            retained.cleanupReason shouldNotBe null
            path.resolve("local.txt").readText() shouldBe "Unsaved work"
            Files.delete(path.resolve("local.txt"))
            lifecycle.cleanupDeleted(root.id).status shouldBe CaseResourceStatus.REMOVED
            io.mockk.verify(exactly = 0) { cases.save(any()) }
        }

        "a root case gets a detached worktree without creating a branch" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Corriger les exports")

            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)

            ready.status shouldBe CaseResourceStatus.READY
            ready.branchName shouldBe null
            val worktree = f.storage.caseRoot(f.namespaceId, root.id, root.metadata.created).resolve("repo")
            worktree.resolve("README.md").readText() shouldBe "v1\n"
            worktree.resolve(".git").exists() shouldBe true
            rawGit(worktree, "branch", "--show-current").trim() shouldBe ""
            rawGit(worktree, "rev-parse", "HEAD").trim() shouldBe ready.baseSha
        }

        "preparation is idempotent" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Idempotent")
            val created = binding(f, root, configured)

            val first = f.provisioner.ensureReady(created, configured, root)
            val second = f.provisioner.ensureReady(f.bindings.findByRootCaseId(root.id)!!, configured, root)

            second.branchName shouldBe first.branchName
            second.baseSha shouldBe first.baseSha
        }

        "a retry keeps the frozen base commit even after the main branch moved" {
            val f = fixture()
            val origin = originRepository()
            val configured = settings(f.namespaceId, origin)
            val root = rootCase(f.namespaceId, "Frozen base")
            val created = binding(f, root, configured)

            val first = f.provisioner.ensureReady(created, configured, root)
            val frozen = requireNotNull(first.baseSha)

            // The branch moves on, then the family is re-prepared from scratch.
            advanceOrigin(origin)
            val worktree = f.storage.caseRoot(f.namespaceId, root.id, root.metadata.created).resolve("repo")
            worktree.toFile().deleteRecursively()
            f.bindings.markStatus(first.id, CaseResourceStatus.FAILED)

            val retried = f.provisioner.ensureReady(f.bindings.findByRootCaseId(root.id)!!, configured, root)

            retried.baseSha shouldBe frozen
            retried.branchName shouldBe first.branchName
        }

        "a new case fetches the latest remote base without changing an existing dirty worktree" {
            val f = fixture()
            val origin = originRepository()
            val configured = settings(f.namespaceId, origin)
            val first = rootCase(f.namespaceId, "Existing")
            val original = f.provisioner.ensureReady(binding(f, first, configured), configured, first)
            val path = f.provisioner.worktreePath(first)
            rawGit(path, "switch", "-c", "agent-feature")
            path.resolve("README.md").writeText("local unfinished edit\n")
            rawGit(path, "add", "README.md")
            val indexBefore = rawGit(path, "diff", "--cached")
            advanceOrigin(origin)
            val latest = rawGit(origin, "rev-parse", "HEAD").trim()
            val next = rootCase(f.namespaceId, "New")
            val fresh = f.provisioner.ensureReady(binding(f, next, configured), configured, next)
            fresh.baseSha shouldBe latest
            rawGit(f.provisioner.worktreePath(next), "rev-parse", "HEAD").trim() shouldBe latest
            rawGit(path, "rev-parse", "HEAD").trim() shouldBe original.baseSha
            rawGit(path, "branch", "--show-current").trim() shouldBe "agent-feature"
            rawGit(path, "diff", "--cached") shouldBe indexBefore
            path.resolve("README.md").readText() shouldBe "local unfinished edit\n"
            // Ordinary agent fetches use remote-tracking refs and preserve local branches too.
            rawGit(path, "fetch", "origin")
            rawGit(path, "rev-parse", "origin/main").trim() shouldBe latest
            rawGit(path, "rev-parse", "HEAD").trim() shouldBe original.baseSha
        }

        "two root cases with the same title get independent detached worktrees" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val first = rootCase(f.namespaceId, "Meme titre")
            val second = rootCase(f.namespaceId, "Meme titre")

            val a = f.provisioner.ensureReady(binding(f, first, configured), configured, first)
            val b = f.provisioner.ensureReady(binding(f, second, configured), configured, second)

            a.branchName shouldBe null
            b.branchName shouldBe null
            rawGit(f.storage.namespaceGitDirectory(f.namespaceId), "for-each-ref", "--format=%(refname)", "refs/heads/").trim() shouldBe "refs/heads/main"
            f.storage.caseRoot(f.namespaceId, first.id, first.metadata.created) shouldNotBe
                f.storage.caseRoot(f.namespaceId, second.id, second.metadata.created)
        }

        "a retry preserves a branch created by an agent and its local work" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Agent workflow")
            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            rawGit(path, "switch", "-c", "agent-chosen-name")
            path.resolve("local.txt").writeText("work in progress")
            f.provisioner.ensureReady(ready, configured, root)
            rawGit(path, "branch", "--show-current").trim() shouldBe "agent-chosen-name"
            path.resolve("local.txt").readText() shouldBe "work in progress"
        }

        listOf("before metadata rename", "before pointer rewrite").forEach { crashPoint ->
            "retry recovers a creation interrupted $crashPoint without discarding local files" {
                val f = fixture()
                val configured = settings(f.namespaceId, originRepository())
                val root = rootCase(f.namespaceId, "Interrupted registration")
                val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
                val path = f.provisioner.worktreePath(root)
                val pinned = f.storage.namespaceGitDirectory(f.namespaceId).toAbsolutePath().resolve("worktrees/${root.id}")
                val allocated = pinned.resolveSibling("repo")
                if (crashPoint == "before metadata rename") Files.move(pinned, allocated)
                path.resolve(".git").writeText("gitdir: $allocated\n")
                path.resolve("local.txt").writeText("Keep the interrupted workspace")
                val interrupted = f.bindings.update(ready.copy(status = CaseResourceStatus.REQUESTED))

                f.provisioner.ensureReady(interrupted, configured, root).status shouldBe CaseResourceStatus.READY

                path.resolve("local.txt").readText() shouldBe "Keep the interrupted workspace"
                rawGit(path, "rev-parse", "HEAD").trim() shouldBe ready.baseSha
                Path.of(path.resolve(".git").readText().trim().removePrefix("gitdir: ")).toRealPath() shouldBe pinned.toRealPath()
            }
        }

        "retry refuses a worktree pointer redirected to another case" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "First")
            val other = rootCase(f.namespaceId, "Other")
            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            f.provisioner.ensureReady(binding(f, other, configured), configured, other)
            val path = f.provisioner.worktreePath(root)
            path.resolve(".git").writeText(f.provisioner.worktreePath(other).resolve(".git").readText())
            shouldThrow<IllegalStateException> { f.provisioner.ensureReady(ready, configured, root) }
            f.bindings.findByRootCaseId(root.id)!!.status shouldBe CaseResourceStatus.FAILED
            path.resolve("README.md").readText() shouldBe "v1\n"
        }

        "a populated target directory fails loudly instead of being wiped" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Occupied")
            val worktree = f.storage.caseRoot(f.namespaceId, root.id, root.metadata.created).resolve("repo")
            Files.createDirectories(worktree)
            worktree.resolve("stray-upload.txt").writeText("uploaded before the workspace was ready\n")

            val error = shouldThrow<IllegalStateException> { f.provisioner.ensureReady(binding(f, root, configured), configured, root) }

            error.message!! shouldContain "already holds"
            worktree.resolve("stray-upload.txt").exists() shouldBe true
            f.bindings.findByRootCaseId(root.id)!!.status shouldBe CaseResourceStatus.FAILED
        }

        "an empty target directory is fine, since a tool grant routinely creates one" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Pre created")
            Files.createDirectories(f.storage.caseRoot(f.namespaceId, root.id, root.metadata.created).resolve("repo"))

            f.provisioner.ensureReady(binding(f, root, configured), configured, root).status shouldBe CaseResourceStatus.READY
        }

        "the configured setup command runs in the worktree" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository(), setupCommand = "echo prepared > setup-marker.txt")
            val root = rootCase(f.namespaceId, "With setup")

            f.provisioner.ensureReady(binding(f, root, configured), configured, root)

            val worktree = f.storage.caseRoot(f.namespaceId, root.id, root.metadata.created).resolve("repo")
            worktree.resolve("setup-marker.txt").readText().trim() shouldBe "prepared"
        }

        "a failing setup command leaves the workspace unusable rather than silently ready" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository(), setupCommand = "exit 3")
            val root = rootCase(f.namespaceId, "Broken setup")

            shouldThrow<IllegalStateException> { f.provisioner.ensureReady(binding(f, root, configured), configured, root) }

            f.bindings.findByRootCaseId(root.id)!!.status shouldBe CaseResourceStatus.FAILED
        }
    })

/** Minimal in-memory [CaseResourceBindingService] for the provisioning tests. */
class InMemoryCaseResourceBindingService : CaseResourceBindingService {
    private val rows = mutableMapOf<UUID, CaseResourceBinding>()

    override fun create(entity: CaseResourceBinding): CaseResourceBinding = entity.also { rows[it.id] = it }

    override fun update(entity: CaseResourceBinding): CaseResourceBinding = entity.also { rows[it.id] = it }

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<CaseResourceBinding> = ids.mapNotNull { rows[it] }.filter { withRemoved || !it.metadata.removed }

    override fun findByParent(parentId: UUID): List<CaseResourceBinding> = rows.values.filter { it.namespaceId == parentId }

    override fun findByRootCaseId(rootCaseId: UUID): CaseResourceBinding? =
        rows.values.firstOrNull { it.rootCaseId == rootCaseId && !it.metadata.removed }

    override fun findByStatusIn(
        statuses: Collection<CaseResourceStatus>,
        limit: Int,
    ): List<CaseResourceBinding> =
        rows.values
            .filter { !it.metadata.removed && it.status in statuses }
            .sortedBy { it.metadata.created }
            .take(limit)

    override fun delete(id: UUID): Boolean =
        rows[id]?.let { rows[id] = it.copy(metadata = it.metadata.copy(removed = true)); true } ?: false

    override fun deleteByParent(parentId: UUID): Int = findByParent(parentId).count { delete(it.id) }

    override fun markStatus(
        id: UUID,
        status: CaseResourceStatus,
        failureReason: String?,
    ): CaseResourceBinding {
        val current = requireNotNull(rows[id]) { "binding $id not found" }
        return current.copy(status = status, failureReason = failureReason).also { rows[id] = it }
    }
}

/** Minimal in-memory [RepositoryCheckoutService] shared by the workspace tests. */
class InMemoryRepositoryCheckouts : RepositoryCheckoutService {
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
