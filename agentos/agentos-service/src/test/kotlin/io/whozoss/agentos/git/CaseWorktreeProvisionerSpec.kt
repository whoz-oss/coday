package io.whozoss.agentos.git

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import io.whozoss.agentos.git.core.GitCommandException
import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitExecutionProperties
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

        "only case deletion triggers cleanup" {
                val f = fixture()
                val configured = settings(f.namespaceId, originRepository(), "printf cache > \"${'$'}HOME/cache\"")
                var root = rootCase(f.namespaceId, "Delete")
                val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
                val path = f.provisioner.worktreePath(root)
                val support = f.storage.workspaceSupportDirectory(f.namespaceId, root.id)
                support.resolve("cache").readText() shouldBe "cache"
                val outside = Files.createTempDirectory("setup-external-").resolve("keep.txt")
                outside.writeText("Keep external files")
                Files.createSymbolicLink(support.resolve("link"), outside.parent)
                rawGit(path, "switch", "-c", "workflow/keep-branch")
                val cases = mockk<io.whozoss.agentos.caseFlow.CaseRepository> {
                    every { findByIds(any(), any()) } answers { listOf(root) }
                    every { findIncludingRemovedByNamespace(any()) } answers { listOf(root) }
                }
                val roots = GitExchangeRootResolver(cases, f.bindings, f.storage, jacksonObjectMapper())
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
                support.exists() shouldBe false
                outside.readText() shouldBe "Keep external files"
                path.parent.resolve("attachment.txt").readText() shouldBe "Keep this document"
                rawGit(f.storage.namespaceGitDirectory(f.namespaceId), "rev-parse", "refs/heads/workflow/keep-branch").trim() shouldBe ready.baseSha
                lifecycle.cleanupDeleted(root.id).status shouldBe CaseResourceStatus.REMOVED
                io.mockk.verify(exactly = 0) { cases.save(any()) }
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
            val roots = GitExchangeRootResolver(cases, f.bindings, f.storage, jacksonObjectMapper())
            var running = false
            val runtime = mockk<io.whozoss.agentos.caseFlow.CaseService> { every { hasRunningExecutions(any()) } answers { running } }
            val lifecycle = GitWorkspaceLifecycleService(f.bindings, cases,
                mockk { every { getObject() } returns runtime }, roots, f.storage, runner,
                mockk { every { findPlugin(any()) } returns null })
            root = root.copy(metadata = root.metadata.copy(removed = true))
            lifecycle.cleanupDeleted(root.id).status shouldBe CaseResourceStatus.READY
            roots.resolveGit(child).requireRepository() shouldBe path
            child = child.copy(metadata = child.metadata.copy(removed = true))
            running = true
            lifecycle.cleanupDeleted(root.id).status shouldBe CaseResourceStatus.DELETING
            path.exists() shouldBe true
            running = false
            lifecycle.cleanupDeleted(root.id).status shouldBe CaseResourceStatus.REMOVED
            path.exists() shouldBe false
        }

        /** Cleanup as the worker runs it once every case of the family has been deleted. */
        fun deletedFamilyLifecycle(f: Fixture, root: Case): GitWorkspaceLifecycleService {
            val removed = root.copy(metadata = root.metadata.copy(removed = true))
            val cases = mockk<io.whozoss.agentos.caseFlow.CaseRepository> {
                every { findByIds(any(), any()) } returns listOf(removed)
                every { findIncludingRemovedByNamespace(any()) } returns listOf(removed)
            }
            return GitWorkspaceLifecycleService(f.bindings, cases,
                mockk { every { getObject() } returns mockk { every { hasRunningExecutions(any()) } returns false } },
                GitExchangeRootResolver(cases, f.bindings, f.storage, jacksonObjectMapper()), f.storage, runner,
                mockk { every { findPlugin(any()) } returns null })
        }

        fun commitIdentity(path: Path) {
            rawGit(path, "config", "user.email", "ci@example.com")
            rawGit(path, "config", "user.name", "CI")
            rawGit(path, "config", "commit.gpgsign", "false")
        }

        "cleanup keeps a deleted family whose worktree holds another linked worktree" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Nested worktree")
            f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            commitIdentity(path)
            path.resolve(".gitignore").writeText(".worktrees/\n")
            rawGit(path, "add", ".gitignore")
            rawGit(path, "commit", "--quiet", "-m", "Ignore nested worktrees")
            rawGit(path, "worktree", "add", "--quiet", "-b", "side", ".worktrees/side")
            path.resolve(".worktrees/side/work.txt").writeText("Unsaved nested work")

            val retained = deletedFamilyLifecycle(f, root).cleanupDeleted(root.id)

            retained.status shouldBe CaseResourceStatus.DELETING
            retained.cleanupReason shouldNotBe null
            path.resolve(".worktrees/side/work.txt").readText() shouldBe "Unsaved nested work"
        }

        "cleanup inspection never runs filters configured inside a submodule" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Submodule filter")
            f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            commitIdentity(path)
            rawGit(path, "-c", "protocol.file.allow=always", "submodule", "add", "--quiet", originRepository().toUri().toString(), "lib")
            rawGit(path, "commit", "--quiet", "-m", "Add library")
            val marker = Files.createTempDirectory("agentos-filter-").resolve("ran")
            // The submodule's own configuration is not the shared repository configuration.
            rawGit(path.resolve("lib"), "config", "filter.evil.clean", "sh -c 'touch $marker; cat'")
            path.resolve("lib/.gitattributes").writeText("* filter=evil\n")
            // Same content, newer timestamp: Git must reread the file through its clean filter.
            path.resolve("lib/README.md").toFile().setLastModified(System.currentTimeMillis() + 5_000) shouldBe true

            deletedFamilyLifecycle(f, root).cleanupDeleted(root.id)

            marker.exists() shouldBe false
        }

        "cleanup completes a worktree removal interrupted after its inspection" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Interrupted removal")
            f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            val common = f.storage.namespaceGitDirectory(f.namespaceId)
            val admin = common.resolve("worktrees/${root.id}")
            val head = rawGit(path, "rev-parse", "HEAD").trim()
            // State left by a SIGKILL during `worktree remove`: inspection passed, the commit is
            // retained, the removal was marked as started and some tracked files are already gone.
            rawGit(common, "update-ref", "refs/agentos/retained/${root.id}", head)
            admin.resolve(GitWorkspaceLifecycleService.REMOVAL_MARKER).writeText("${root.id}\n")
            Files.delete(path.resolve("README.md"))

            deletedFamilyLifecycle(f, root).cleanupDeleted(root.id).status shouldBe CaseResourceStatus.REMOVED

            path.exists() shouldBe false
            admin.exists() shouldBe false
            rawGit(common, "rev-parse", "refs/agentos/retained/${root.id}").trim() shouldBe head
        }

        "a removal marker without the retained commit never forces cleanup of local changes" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Planted marker")
            f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            val admin = f.storage.namespaceGitDirectory(f.namespaceId).resolve("worktrees/${root.id}")
            admin.resolve(GitWorkspaceLifecycleService.REMOVAL_MARKER).writeText("${root.id}\n")
            path.resolve("README.md").writeText("Uncommitted change\n")

            deletedFamilyLifecycle(f, root).cleanupDeleted(root.id).status shouldBe CaseResourceStatus.DELETING

            path.resolve("README.md").readText() shouldBe "Uncommitted change\n"
        }

        "dirty deleted worktrees are retained even when Git hides untracked files" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            var root = rootCase(f.namespaceId, "Local work")
            f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            path.resolve("local.txt").writeText("Unsaved work")
            rawGit(path, "config", "status.showUntrackedFiles", "no")
            val cases = mockk<io.whozoss.agentos.caseFlow.CaseRepository> {
                every { findByIds(any(), any()) } answers { listOf(root) }
                every { findIncludingRemovedByNamespace(any()) } answers { listOf(root) }
            }
            val lifecycle = GitWorkspaceLifecycleService(f.bindings, cases,
                mockk { every { getObject() } returns mockk { every { hasRunningExecutions(any()) } returns false } },
                GitExchangeRootResolver(cases, f.bindings, f.storage, jacksonObjectMapper()), f.storage, runner,
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

        "cleanup failures do not publish exception diagnostics and preserve the worktree" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Cleanup failure").let { it.copy(metadata = it.metadata.copy(removed = true)) }
            f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            val cases = mockk<io.whozoss.agentos.caseFlow.CaseRepository> {
                every { findByIds(any(), any()) } returns listOf(root)
                every { findIncludingRemovedByNamespace(any()) } returns listOf(root)
            }
            val lifecycle = GitWorkspaceLifecycleService(f.bindings, cases,
                mockk { every { getObject() } returns mockk {
                    every { hasRunningExecutions(any()) } throws IllegalStateException("synthetic-secret")
                } },
                GitExchangeRootResolver(cases, f.bindings, f.storage, jacksonObjectMapper()), f.storage, runner,
                mockk { every { findPlugin(any()) } returns null })
            val retained = lifecycle.cleanupDeleted(root.id)
            retained.status shouldBe CaseResourceStatus.DELETING
            retained.cleanupReason shouldBe "Cannot confirm that the deleted case has stopped its execution."
            path.resolve("README.md").readText() shouldBe "v1\n"
        }

        "cleanup preserves detached commits through garbage collection without creating a branch" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            var root = rootCase(f.namespaceId, "Detached work")
            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            rawGit(path, "config", "user.email", "ci@example.com")
            rawGit(path, "config", "user.name", "CI")
            path.resolve("README.md").writeText("Committed without a branch\n")
            rawGit(path, "-c", "commit.gpgsign=false", "commit", "-am", "Detached work")
            val head = rawGit(path, "rev-parse", "HEAD").trim()
            head shouldNotBe ready.baseSha
            val cases = mockk<io.whozoss.agentos.caseFlow.CaseRepository> {
                every { findByIds(any(), any()) } answers { listOf(root) }
                every { findIncludingRemovedByNamespace(any()) } answers { listOf(root) }
            }
            val lifecycle = GitWorkspaceLifecycleService(f.bindings, cases,
                mockk { every { getObject() } returns mockk { every { hasRunningExecutions(any()) } returns false } },
                GitExchangeRootResolver(cases, f.bindings, f.storage, jacksonObjectMapper()), f.storage, runner,
                mockk { every { findPlugin(any()) } returns null })
            root = root.copy(metadata = root.metadata.copy(removed = true))
            lifecycle.cleanupDeleted(root.id).status shouldBe CaseResourceStatus.REMOVED
            path.exists() shouldBe false
            val common = f.storage.namespaceGitDirectory(f.namespaceId)
            rawGit(common, "gc", "--prune=now")
            rawGit(common, "show", "$head:README.md").trim() shouldBe "Committed without a branch"
            rawGit(common, "rev-parse", "refs/agentos/retained/${root.id}").trim() shouldBe head
            rawGit(common, "for-each-ref", "--format=%(refname)", "refs/heads/").trim() shouldBe ""
        }

        listOf("creation", "cleanup existing checkout", "cleanup missing checkout").forEach { operation ->
            "$operation preserves every registration file of another temporarily absent family" {
                val f = fixture()
                val configured = settings(f.namespaceId, originRepository())
                val first = rootCase(f.namespaceId, "Temporarily unavailable checkout")
                var second = rootCase(f.namespaceId, "Other family")
                f.provisioner.ensureReady(binding(f, first, configured), configured, first)
                val firstPath = f.provisioner.worktreePath(first)
                rawGit(firstPath, "config", "user.email", "ci@example.com")
                rawGit(firstPath, "config", "user.name", "CI")
                firstPath.resolve("README.md").writeText("Detached committed work\n")
                rawGit(firstPath, "-c", "commit.gpgsign=false", "commit", "-am", "Detached work")
                val head = rawGit(firstPath, "rev-parse", "HEAD").trim()
                firstPath.resolve("README.md").writeText("Unique staged work\n")
                rawGit(firstPath, "add", "README.md")
                val staged = rawGit(firstPath, "rev-parse", ":README.md").trim()
                firstPath.resolve("README.md").writeText("Detached committed work\n")
                val common = f.storage.namespaceGitDirectory(f.namespaceId)
                val admin = common.resolve("worktrees/${first.id}")
                admin.resolve("recovery-marker").writeText("Keep all family metadata")
                fun registrationFiles() = Files.walk(admin).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.toList().associate {
                        admin.relativize(it).toString() to Files.readAllBytes(it).toList()
                    }
                }
                val metadata = registrationFiles()
                val secondBinding = binding(f, second, configured)
                if (operation != "creation") f.provisioner.ensureReady(secondBinding, configured, second)
                val offline = firstPath.resolveSibling("temporarily-offline")
                Files.move(firstPath, offline)
                try {
                    if (operation == "creation") {
                        f.provisioner.ensureReady(secondBinding, configured, second).status shouldBe CaseResourceStatus.READY
                    } else {
                        val secondPath = f.provisioner.worktreePath(second)
                        if (operation == "cleanup missing checkout") secondPath.toFile().deleteRecursively() shouldBe true
                        second = second.copy(metadata = second.metadata.copy(removed = true))
                        val cases = mockk<io.whozoss.agentos.caseFlow.CaseRepository> {
                            every { findByIds(any(), any()) } answers {
                                listOf(first, second).filter { it.id in firstArg<Collection<UUID>>() }
                            }
                            every { findIncludingRemovedByNamespace(any()) } answers { listOf(first, second) }
                        }
                        val lifecycle = GitWorkspaceLifecycleService(f.bindings, cases,
                            mockk { every { getObject() } returns mockk { every { hasRunningExecutions(any()) } returns false } },
                            GitExchangeRootResolver(cases, f.bindings, f.storage, jacksonObjectMapper()), f.storage, runner,
                            mockk { every { findPlugin(any()) } returns null })
                        lifecycle.cleanupDeleted(second.id).status shouldBe CaseResourceStatus.REMOVED
                        common.resolve("worktrees/${second.id}").exists() shouldBe false
                        rawGit(common, "rev-parse", "refs/agentos/retained/${second.id}").trim() shouldBe
                            f.bindings.findByRootCaseId(second.id)!!.baseSha
                    }
                    registrationFiles() shouldBe metadata
                } finally {
                    Files.move(offline, firstPath)
                }
                rawGit(firstPath, "rev-parse", "HEAD").trim() shouldBe head
                rawGit(firstPath, "show", ":README.md").trim() shouldBe "Unique staged work"
                rawGit(common, "gc", "--prune=now")
                rawGit(common, "show", "$head:README.md").trim() shouldBe "Detached committed work"
                rawGit(common, "cat-file", "-p", staged).trim() shouldBe "Unique staged work"
                rawGit(common, "for-each-ref", "--format=%(refname)", "refs/heads/").trim() shouldBe ""
            }
        }

        "targeted cleanup resolves storage aliases when the entire Exchange is already missing" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Missing Exchange")
            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root).toAbsolutePath()
            val common = f.storage.namespaceGitDirectory(f.namespaceId).toRealPath()
            val admin = common.resolve("worktrees/${root.id}")
            val aliases = Files.createTempDirectory("workspace-alias-")
            val alias = Files.createSymbolicLink(aliases.resolve("storage"), path.parent.parent.toRealPath())
            val aliasedPointer = alias.resolve(path.parent.fileName).resolve("repo/.git")
            admin.resolve("gitdir").writeText(admin.relativize(aliasedPointer).toString() + "\n")
            path.parent.toFile().deleteRecursively() shouldBe true
            removeMissingWorktreeRegistration(runner, common, root.id, path)
            Files.exists(admin) shouldBe false
            rawGit(common, "rev-parse", "refs/agentos/retained/${root.id}").trim() shouldBe ready.baseSha
            Files.delete(alias)
            Files.delete(aliases)
        }

        "targeted cleanup refuses a registration symlink without following it outside the repository" {
            val common = Files.createTempDirectory("cleanup-registration-")
            val outside = Files.createTempDirectory("cleanup-external-")
            val rootId = UUID.randomUUID()
            outside.resolve("HEAD").writeText("Must not read or remove external metadata")
            Files.createDirectories(common.resolve("worktrees"))
            Files.createSymbolicLink(common.resolve("worktrees/$rootId"), outside)
            val noGit = mockk<GitCommandRunner>()
            shouldThrow<IllegalStateException> {
                removeMissingWorktreeRegistration(noGit, common, rootId, common.resolve("missing/repo"))
            }
            io.mockk.verify(exactly = 0) { noGit.runOrThrow(any()) }
            outside.resolve("HEAD").readText() shouldBe "Must not read or remove external metadata"
        }

        "failed commit retention preserves the deleted family's missing worktree registration" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Ref failure")
            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            path.toFile().deleteRecursively() shouldBe true
            val common = f.storage.namespaceGitDirectory(f.namespaceId)
            // A ref at the parent name prevents creation of a descendant retention ref.
            rawGit(common, "update-ref", "refs/agentos/retained", ready.baseSha!!)
            shouldThrow<GitCommandException> { removeMissingWorktreeRegistration(runner, common, root.id, path) }
            common.resolve("worktrees/${root.id}/HEAD").exists() shouldBe true
        }

        "targeted cleanup refuses a registration pointing to another family's checkout" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Deleted family")
            val other = rootCase(f.namespaceId, "Surviving family")
            f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            f.provisioner.ensureReady(binding(f, other, configured), configured, other)
            val path = f.provisioner.worktreePath(root)
            val otherPath = f.provisioner.worktreePath(other)
            val common = f.storage.namespaceGitDirectory(f.namespaceId)
            val admin = common.resolve("worktrees/${root.id}")
            admin.resolve("gitdir").writeText("${otherPath.resolve(".git")}\n")
            path.toFile().deleteRecursively() shouldBe true
            shouldThrow<IllegalStateException> { removeMissingWorktreeRegistration(runner, common, root.id, path) }
            admin.resolve("index").exists() shouldBe true
            otherPath.resolve("README.md").readText() shouldBe "v1\n"
        }

        listOf("locked", "modules", "staged work").forEach { reason ->
            "targeted cleanup preserves a missing checkout with $reason" {
                val f = fixture()
                val configured = settings(f.namespaceId, originRepository())
                val root = rootCase(f.namespaceId, "Protected metadata")
                f.provisioner.ensureReady(binding(f, root, configured), configured, root)
                val path = f.provisioner.worktreePath(root)
                val common = f.storage.namespaceGitDirectory(f.namespaceId)
                val admin = common.resolve("worktrees/${root.id}")
                when (reason) {
                    "locked" -> admin.resolve("locked").writeText("Keep this workspace")
                    "modules" -> Files.createDirectories(admin.resolve("modules/lib")).resolve("keep").writeText("Local module data")
                    else -> {
                        path.resolve("README.md").writeText("Only stored in the index\n")
                        rawGit(path, "add", "README.md")
                    }
                }
                val index = Files.readAllBytes(admin.resolve("index")).toList()
                path.toFile().deleteRecursively() shouldBe true
                shouldThrow<Exception> { removeMissingWorktreeRegistration(runner, common, root.id, path) }
                Files.readAllBytes(admin.resolve("index")).toList() shouldBe index
                admin.resolve("HEAD").exists() shouldBe true
                if (reason == "modules") admin.resolve("modules/lib/keep").readText() shouldBe "Local module data"
            }
        }

        "a root case gets a detached worktree without creating a branch" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Corriger les exports")

            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)

            ready.status shouldBe CaseResourceStatus.READY
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

            second.baseSha shouldBe first.baseSha
        }

        "a retry after explicit worktree removal keeps the frozen base after the main branch moved" {
            val f = fixture()
            val origin = originRepository()
            val configured = settings(f.namespaceId, origin)
            val root = rootCase(f.namespaceId, "Frozen base")
            val created = binding(f, root, configured)

            val first = f.provisioner.ensureReady(created, configured, root)
            val frozen = requireNotNull(first.baseSha)

            // Explicit Git removal removes this clean checkout and its registration together.
            advanceOrigin(origin)
            val worktree = f.provisioner.worktreePath(root)
            rawGit(f.storage.namespaceGitDirectory(f.namespaceId), "worktree", "remove", worktree.toString())
            worktree.exists() shouldBe false
            f.bindings.markStatus(first.id, CaseResourceStatus.FAILED)

            val retried = f.provisioner.ensureReady(f.bindings.findByRootCaseId(root.id)!!, configured, root)

            retried.baseSha shouldBe frozen
        }

        "retry retains an absent family's index and succeeds when its checkout returns" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Unavailable checkout")
            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            path.resolve("README.md").writeText("Unique staged work\n")
            rawGit(path, "add", "README.md")
            path.resolve("README.md").writeText("Different working copy\n")
            val admin = f.storage.namespaceGitDirectory(f.namespaceId).resolve("worktrees/${root.id}")
            val index = Files.readAllBytes(admin.resolve("index")).toList()
            val offline = path.resolveSibling("offline")
            Files.move(path, offline)
            try {
                val requested = f.bindings.update(ready.copy(status = CaseResourceStatus.REQUESTED))
                shouldThrow<GitCommandException> { f.provisioner.ensureReady(requested, configured, root) }
                Files.readAllBytes(admin.resolve("index")).toList() shouldBe index
            } finally {
                Files.move(offline, path)
            }
            f.provisioner.ensureReady(f.bindings.findByRootCaseId(root.id)!!, configured, root).status shouldBe CaseResourceStatus.READY
            rawGit(path, "show", ":README.md").trim() shouldBe "Unique staged work"
            path.resolve("README.md").readText() shouldBe "Different working copy\n"
        }

        "retry adopts a valid registration whose pointers are relative" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Relative Git pointers")
            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            val pinned = f.storage.namespaceGitDirectory(f.namespaceId).toAbsolutePath().resolve("worktrees/${root.id}")
            val allocated = pinned.resolveSibling("repo")
            Files.move(pinned, allocated)
            // Git's relative-pointer format; construct it directly so this regression also runs
            // with Git versions that predate the worktree.useRelativePaths configuration switch.
            path.resolve(".git").writeText("gitdir: ${path.relativize(allocated)}\n")
            allocated.resolve("gitdir").writeText("${allocated.relativize(path.resolve(".git"))}\n")
            val requested = f.bindings.update(ready.copy(status = CaseResourceStatus.REQUESTED))
            f.provisioner.ensureReady(requested, configured, root).status shouldBe CaseResourceStatus.READY
            rawGit(path, "rev-parse", "HEAD").trim() shouldBe ready.baseSha
            pinned.resolve("index").exists() shouldBe true
            allocated.exists() shouldBe false
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

            rawGit(f.storage.namespaceGitDirectory(f.namespaceId), "for-each-ref", "--format=%(refname)", "refs/heads/").trim() shouldBe ""
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

        "a checkout interrupted before the workspace was ever ready is set aside and recreated" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val first = rootCase(f.namespaceId, "Prepare namespace repository")
            val ready = f.provisioner.ensureReady(binding(f, first, configured), configured, first)
            val root = rootCase(f.namespaceId, "Interrupted checkout")
            val pending = f.bindings.update(binding(f, root, configured).copy(baseSha = ready.baseSha))
            val common = f.storage.namespaceGitDirectory(f.namespaceId)
            val path = f.provisioner.worktreePath(root)
            Files.createDirectories(path.parent)
            // Git's own pre-checkout state has valid HEAD/pointers but no index or tracked files.
            rawGit(common, "worktree", "add", "--no-checkout", "--detach", path.toString(), ready.baseSha!!)
            val admin = Path.of(path.resolve(".git").readText().trim().removePrefix("gitdir: "))
            admin.resolve("locked").writeText("initializing\n")
            path.resolve("README.md").exists() shouldBe false
            admin.resolve("index").exists() shouldBe false
            path.resolve("local.txt").writeText("Preserve partial work")

            f.provisioner.ensureReady(pending, configured, root).status shouldBe CaseResourceStatus.READY

            path.resolve("README.md").readText() shouldBe "v1\n"
            path.resolve("local.txt").exists() shouldBe false
            // Nothing is deleted: the interrupted checkout is kept outside both browsable Exchanges.
            val support = f.storage.workspaceSupportDirectory(f.namespaceId, root.id)
            val setAside = Files.list(support).use { entries -> entries.toList() }
                .single { it.fileName.toString().startsWith("interrupted-checkout-") }
            setAside.resolve("local.txt").readText() shouldBe "Preserve partial work"
        }

        listOf("initializing", "missing index").forEach { incompleteState ->
            "retry refuses an incomplete checkout with $incompleteState and preserves local work" {
                val f = fixture()
                val configured = settings(f.namespaceId, originRepository())
                val root = rootCase(f.namespaceId, "Incomplete checkout")
                val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
                val path = f.provisioner.worktreePath(root)
                val admin = f.storage.namespaceGitDirectory(f.namespaceId).resolve("worktrees/${root.id}")
                val index = Files.readAllBytes(admin.resolve("index"))
                path.resolve("local.txt").writeText("Keep my work")
                if (incompleteState == "initializing") admin.resolve("locked").writeText("initializing\n")
                else Files.delete(admin.resolve("index"))
                val requested = f.bindings.update(ready.copy(status = CaseResourceStatus.REQUESTED))

                shouldThrow<IllegalStateException> { f.provisioner.ensureReady(requested, configured, root) }
                f.bindings.findByRootCaseId(root.id)!!.status shouldBe CaseResourceStatus.FAILED
                path.resolve("local.txt").readText() shouldBe "Keep my work"
                // An ordinary retry cannot overwrite the incomplete checkout or run setup over it.
                shouldThrow<IllegalStateException> {
                    f.provisioner.ensureReady(f.bindings.findByRootCaseId(root.id)!!, configured, root)
                }
                path.resolve("local.txt").readText() shouldBe "Keep my work"

                // Explicit inspection/recovery restores the valid checkout; retry then converges.
                Files.deleteIfExists(admin.resolve("locked"))
                Files.write(admin.resolve("index"), index)
                f.provisioner.ensureReady(f.bindings.findByRootCaseId(root.id)!!, configured, root).status shouldBe CaseResourceStatus.READY
                path.resolve("local.txt").readText() shouldBe "Keep my work"
            }
        }

        "setup HOME and tool caches stay outside both browsable Exchanges" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository(),
                setupCommand = "mkdir -p \"${'$'}HOME/.npm\" \"${'$'}XDG_CACHE_HOME/tool\"; touch \"${'$'}HOME/.npm/log\" \"${'$'}XDG_CACHE_HOME/tool/item\"")
            val root = rootCase(f.namespaceId, "Setup cache")
            f.provisioner.ensureReady(binding(f, root, configured), configured, root).status shouldBe CaseResourceStatus.READY
            val path = f.provisioner.worktreePath(root)
            rawGit(path, "status", "--porcelain").trim() shouldBe ""
            val support = f.storage.workspaceSupportDirectory(f.namespaceId, root.id)
            support.resolve(".npm/log").exists() shouldBe true
            support.resolve(".cache/tool/item").exists() shouldBe true
            path.parent.resolve(".setup-home").exists() shouldBe false
            f.storage.listManifest(path.parent, io.whozoss.agentos.sdk.api.exchange.ExchangeScope.CASE)
                .map { it.path } shouldBe listOf("repo/README.md")
            f.storage.listManifest(f.storage.namespaceRoot(f.namespaceId), io.whozoss.agentos.sdk.api.exchange.ExchangeScope.NAMESPACE)
                .isEmpty() shouldBe true
        }

        "the configured setup command runs in the worktree" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository(), setupCommand = "echo prepared > setup-marker.txt")
            val root = rootCase(f.namespaceId, "With setup")

            f.provisioner.ensureReady(binding(f, root, configured), configured, root)

            val worktree = f.storage.caseRoot(f.namespaceId, root.id, root.metadata.created).resolve("repo")
            worktree.resolve("setup-marker.txt").readText().trim() shouldBe "prepared"
        }

        "a failing setup command leaves the workspace unusable without publishing its output" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository(), setupCommand = "echo synthetic-secret; exit 1")
            val root = rootCase(f.namespaceId, "Broken setup")
            val logger = org.slf4j.LoggerFactory.getLogger("io.whozoss.agentos.git") as ch.qos.logback.classic.Logger
            val logs = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().also { it.start() }
            logger.addAppender(logs)
            try {
                shouldThrow<IllegalStateException> { f.provisioner.ensureReady(binding(f, root, configured), configured, root) }
                val failed = f.bindings.findByRootCaseId(root.id)!!
                failed.status shouldBe CaseResourceStatus.FAILED
                failed.failureReason!!.contains("synthetic-secret") shouldBe false
                failed.failureReason shouldContain "Setup failed"
                logs.list.any { it.loggerName == CaseWorktreeProvisioner::class.java.name && it.level == ch.qos.logback.classic.Level.ERROR } shouldBe true
                val rendered = logs.list.joinToString("\n") {
                    it.formattedMessage + (it.throwableProxy?.let(ch.qos.logback.classic.spi.ThrowableProxyUtil::asString) ?: "")
                }
                rendered.contains("synthetic-secret") shouldBe false
            } finally {
                logger.detachAppender(logs)
                logs.stop()
            }
        }
    })

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
