package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.StringSpec
import io.kotest.data.forAll
import io.kotest.data.headers
import io.kotest.data.row
import io.kotest.data.table
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.git.GitWorktreeTestKit.binding
import io.whozoss.agentos.git.GitWorktreeTestKit.commitIdentity
import io.whozoss.agentos.git.GitWorktreeTestKit.deletedFamilyLifecycle
import io.whozoss.agentos.git.GitWorktreeTestKit.fixture
import io.whozoss.agentos.git.GitWorktreeTestKit.originRepository
import io.whozoss.agentos.git.GitWorktreeTestKit.rawGit
import io.whozoss.agentos.git.GitWorktreeTestKit.rootCase
import io.whozoss.agentos.git.GitWorktreeTestKit.runner
import io.whozoss.agentos.git.GitWorktreeTestKit.settings
import io.whozoss.agentos.git.core.GitCommandException
import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Preparation recovery and the cleanup of deleted families.
 *
 * Cleanup runs against real repositories: what it must never lose (uncommitted work, nested
 * worktrees, detached commits) is Git's own state.
 */
@EnabledIf(PosixOnly::class)
class GitWorkspaceLifecycleServiceSpec :
    StringSpec({
        timeout = 180_000

        fun failed(
            bindings: InMemoryCaseResourceBindingService,
            setup: SetupState,
        ): CaseResourceBinding =
            bindings.create(CaseResourceBinding(
                rootCaseId = UUID.randomUUID(), namespaceId = UUID.randomUUID(), integrationConfigId = UUID.randomUUID(),
                status = CaseResourceStatus.FAILED, failureReason = "Cannot fetch the case base.",
                setup = setup,
            ))

        fun lifecycleOf(bindings: CaseResourceBindingService) =
            GitWorkspaceLifecycleService(bindings, mockk(), mockk(), mockk(), mockk(), mockk(), mockk())

        "acknowledging a setup replay is refused when no setup was interrupted" {
            table(
                headers("setup"),
                row(SetupState.NOT_STARTED),
                row(SetupState.COMPLETED),
            ).forAll { setup ->
                val bindings = InMemoryCaseResourceBindingService()
                val binding = failed(bindings, setup)

                shouldThrow<ConflictException> { lifecycleOf(bindings).acknowledgeSetup(binding.rootCaseId) }.message shouldBe
                    "No interrupted setup to acknowledge: retry the preparation instead"
                bindings.findByRootCaseId(binding.rootCaseId) shouldBe binding
            }
        }

        "acknowledging an interrupted setup requests a new preparation that runs it again" {
            val bindings = InMemoryCaseResourceBindingService()
            val binding = failed(bindings, SetupState.STARTED)

            val requested = lifecycleOf(bindings).acknowledgeSetup(binding.rootCaseId)

            requested.status shouldBe CaseResourceStatus.REQUESTED
            requested.failureReason shouldBe null
            requested.setup shouldBe SetupState.NOT_STARTED
        }

        fun rows(bindings: InMemoryCaseResourceBindingService, count: Long = 7): List<CaseResourceBinding> = (1L..count).map { id ->
            bindings.create(CaseResourceBinding(
                metadata = EntityMetadata(id = UUID(0, id), created = Instant.parse("2026-01-01T00:00:00Z")),
                rootCaseId = UUID.randomUUID(), namespaceId = UUID.randomUUID(),
                integrationConfigId = UUID.randomUUID(), status = CaseResourceStatus.READY,
            ))
        }

        "cleanup visits the next page after earlier bindings disappear and retries on the next sweep" {
            val bindings = InMemoryCaseResourceBindingService()
            val rows = rows(bindings)
            val visited = mutableListOf<UUID>()
            val cases = mockk<CaseRepository> {
                every { findByIds(any(), true) } answers {
                    visited.addAll(firstArg<Collection<UUID>>())
                    emptyList<Case>()
                }
            }
            val lifecycle = GitWorkspaceLifecycleService(bindings, cases, mockk(), mockk(), mockk(), mockk(), mockk())
            lifecycle.cleanupDeletedCases()
            visited shouldBe rows.take(5).map { it.rootCaseId }
            rows.take(5).forEach { bindings.delete(it.id) }
            lifecycle.cleanupDeletedCases()
            visited shouldBe rows.map { it.rootCaseId }
            lifecycle.cleanupDeletedCases() // The short final page reset the cursor; retry surviving rows.
            visited shouldBe rows.map { it.rootCaseId } + rows.takeLast(2).map { it.rootCaseId }
        }

        "a full final page starts the next cleanup sweep without losing a worker tick" {
            val bindings = InMemoryCaseResourceBindingService()
            val rows = rows(bindings, 5)
            val visited = mutableListOf<UUID>()
            val cases = mockk<CaseRepository> {
                every { findByIds(any(), true) } answers {
                    visited.addAll(firstArg<Collection<UUID>>())
                    emptyList<Case>()
                }
            }
            val lifecycle = GitWorkspaceLifecycleService(bindings, cases, mockk(), mockk(), mockk(), mockk(), mockk())
            repeat(2) { lifecycle.cleanupDeletedCases() }
            visited shouldBe rows.map { it.rootCaseId } + rows.map { it.rootCaseId }
        }

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
                    every { findByParent(any()) } answers { listOf(root).filter { !it.metadata.removed } }
                }
                val roots = GitExchangeRootResolver(cases, f.bindings, f.storage)
                val runtime = mockk<io.whozoss.agentos.caseFlow.CaseService> { every { hasRunningExecutions(any()) } returns false; every { getAllActiveCases() } returns emptyList() }
                path.parent.resolve("attachment.txt").writeText("Keep this document")
                val lifecycle = GitWorkspaceLifecycleService(f.bindings, cases,
                    mockk { every { getObject() } returns runtime }, roots, f.storage, runner)
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
                every { findByParent(any()) } answers { listOf(root, child).filter { !it.metadata.removed } }
            }
            val roots = GitExchangeRootResolver(cases, f.bindings, f.storage)
            var running = false
            val childRuntime = mockk<io.whozoss.agentos.caseFlow.CaseRuntime> {
                every { id } returns child.id
                every { namespaceId } returns f.namespaceId
            }
            val runtime = mockk<io.whozoss.agentos.caseFlow.CaseService> {
                every { hasRunningExecutions(any()) } answers { running && child.id in firstArg<Collection<UUID>>() }
                every { getAllActiveCases() } returns listOf(childRuntime)
            }
            val lifecycle = GitWorkspaceLifecycleService(f.bindings, cases,
                mockk { every { getObject() } returns runtime }, roots, f.storage, runner)
            root = root.copy(metadata = root.metadata.copy(removed = true))
            lifecycle.cleanupDeleted(root.id).status shouldBe CaseResourceStatus.READY
            roots.resolveGit(child).also { it.requireUsable() }.repositoryPath shouldBe path
            child = child.copy(metadata = child.metadata.copy(removed = true))
            running = true
            lifecycle.cleanupDeleted(root.id).status shouldBe CaseResourceStatus.DELETING
            path.exists() shouldBe true
            running = false
            lifecycle.cleanupDeleted(root.id).status shouldBe CaseResourceStatus.REMOVED
            path.exists() shouldBe false
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
            retained.cleanupReason shouldBe
                "The worktree contains another Git worktree. Remove it or move it out of the workspace first."
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

            val retained = deletedFamilyLifecycle(f, root).cleanupDeleted(root.id)

            retained.status shouldBe CaseResourceStatus.DELETING
            retained.cleanupReason shouldBe "The worktree contains uncommitted or untracked work."
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
                every { findByParent(any()) } answers { listOf(root).filter { !it.metadata.removed } }
            }
            val lifecycle = GitWorkspaceLifecycleService(f.bindings, cases,
                mockk { every { getObject() } returns mockk { every { hasRunningExecutions(any()) } returns false; every { getAllActiveCases() } returns emptyList() } },
                GitExchangeRootResolver(cases, f.bindings, f.storage), f.storage, runner)
            root = root.copy(metadata = root.metadata.copy(removed = true))
            val retained = lifecycle.cleanupDeleted(root.id)
            retained.status shouldBe CaseResourceStatus.DELETING
            retained.cleanupReason shouldBe "The worktree contains uncommitted or untracked work."
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
                every { findByParent(any()) } answers { listOf(root).filter { !it.metadata.removed } }
            }
            val lifecycle = GitWorkspaceLifecycleService(f.bindings, cases,
                mockk { every { getObject() } returns mockk {
                    every { hasRunningExecutions(any()) } throws IllegalStateException("synthetic-secret")
                    every { getAllActiveCases() } returns emptyList()
                } },
                GitExchangeRootResolver(cases, f.bindings, f.storage), f.storage, runner)
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
                every { findByParent(any()) } answers { listOf(root).filter { !it.metadata.removed } }
            }
            val lifecycle = GitWorkspaceLifecycleService(f.bindings, cases,
                mockk { every { getObject() } returns mockk { every { hasRunningExecutions(any()) } returns false; every { getAllActiveCases() } returns emptyList() } },
                GitExchangeRootResolver(cases, f.bindings, f.storage), f.storage, runner)
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
                            every { findByParent(any()) } answers { listOf(first, second).filter { !it.metadata.removed } }
                        }
                        val lifecycle = GitWorkspaceLifecycleService(f.bindings, cases,
                            mockk { every { getObject() } returns mockk { every { hasRunningExecutions(any()) } returns false; every { getAllActiveCases() } returns emptyList() } },
                            GitExchangeRootResolver(cases, f.bindings, f.storage), f.storage, runner)
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
    })
