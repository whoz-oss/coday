package io.whozoss.agentos.git

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.git.GitWorktreeTestKit.Fixture
import io.whozoss.agentos.git.GitWorktreeTestKit.advanceOrigin
import io.whozoss.agentos.git.GitWorktreeTestKit.binding
import io.whozoss.agentos.git.GitWorktreeTestKit.fixture
import io.whozoss.agentos.git.GitWorktreeTestKit.gitProperties
import io.whozoss.agentos.git.GitWorktreeTestKit.originRepository
import io.whozoss.agentos.git.GitWorktreeTestKit.rawGit
import io.whozoss.agentos.git.GitWorktreeTestKit.rootCase
import io.whozoss.agentos.git.GitWorktreeTestKit.runner
import io.whozoss.agentos.git.GitWorktreeTestKit.settings
import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitCredentials
import io.whozoss.agentos.git.core.GitLayout
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.writeText

/** Observation of a ready workspace prepared by the real provisioner, as the monitor finds it. */
@EnabledIf(PosixOnly::class)
class GitWorkspaceStatusServiceSpec :
    StringSpec({
        timeout = 180_000

        fun statusService(
            f: Fixture,
            settings: GitRepositorySettings,
            hosting: GitHostingProvider,
            statusRunner: GitCommandRunner = runner,
        ) =
            GitWorkspaceStatusService(
                bindings = f.bindings,
                associations = mockk { every { findSettings(f.namespaceId) } returns settings },
                storage = f.storage,
                runner = statusRunner,
                accounts = mockk { every { resolve(settings) } returns GitCredentials.UsernamePassword("test", "unused") },
                hosting = hosting,
                mapper = jacksonObjectMapper().findAndRegisterModules(),
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
            verify(exactly = 0) { hosting.inspect(any(), any(), any()) }
            rawGit(path, "switch", "-c", "workflow/my-branch")
            val local = status.refresh(detached, path)
            local.branchName shouldBe "workflow/my-branch"
            status.summary(local)!!.branchState shouldBe "LOCAL_ONLY"
            verify { hosting.inspect(configured, "workflow/my-branch", null) }
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

        "status observation never moves the origin tracking ref that force-with-lease relies on" {
            val f = fixture()
            val origin = originRepository()
            val configured = settings(f.namespaceId, origin)
            val root = rootCase(f.namespaceId, "Lease")
            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            rawGit(origin, "branch", "feature")
            rawGit(path, "fetch", "--quiet", "origin", "+refs/heads/feature:refs/remotes/origin/feature")
            rawGit(path, "switch", "--quiet", "-c", "feature", "refs/remotes/origin/feature")
            val leased = rawGit(path, "rev-parse", "refs/remotes/origin/feature").trim()
            // A collaborator pushes after the agent's last fetch: the agent's lease must stay stale.
            rawGit(origin, "switch", "--quiet", "feature")
            origin.resolve("README.md").writeText("collaborator\n")
            rawGit(origin, "commit", "--quiet", "-am", "Collaborator change")
            val hosting = mockk<GitHostingProvider> { every { inspect(any(), any(), any()) } returns GitWorkspaceSummary(prState = "NONE") }
            val status = statusService(f, configured, hosting)

            val observed = status.refresh(ready, path)

            rawGit(path, "rev-parse", "refs/remotes/origin/feature").trim() shouldBe leased
            status.summary(observed)!!.remoteSha shouldBe rawGit(origin, "rev-parse", "feature").trim()
            status.summary(observed)!!.branchState shouldBe "PUSHED"
        }

        "status observation never runs filters configured inside a submodule" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Submodule status")
            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            rawGit(path, "config", "user.email", "ci@example.com")
            rawGit(path, "config", "user.name", "CI")
            rawGit(path, "config", "commit.gpgsign", "false")
            rawGit(path, "-c", "protocol.file.allow=always", "submodule", "add", "--quiet", originRepository().toUri().toString(), "lib")
            rawGit(path, "commit", "--quiet", "-m", "Add library")
            val marker = Files.createTempDirectory("agentos-filter-").resolve("ran")
            rawGit(path.resolve("lib"), "config", "filter.evil.clean", "sh -c 'touch $marker; cat'")
            path.resolve("lib/.gitattributes").writeText("* filter=evil\n")
            path.resolve("lib/README.md").toFile().setLastModified(System.currentTimeMillis() + 5_000) shouldBe true
            val hosting = mockk<GitHostingProvider> { every { inspect(any(), any(), any()) } returns GitWorkspaceSummary(prState = "NONE") }

            statusService(f, configured, hosting).refresh(ready, path)

            marker.exists() shouldBe false
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
            verify(exactly = 1) { hosting.inspect(configured, "pr-1301", prHead) }
            // The previous PR must not stick to the case after the agent switches elsewhere.
            rawGit(path, "switch", "-c", "another-task", ready.baseSha!!)
            val switched = status.refresh(observed, path)
            switched.branchName shouldBe "another-task"
            status.summary(switched)!!.prState shouldBe "NONE"
            status.summary(switched)!!.prNumber shouldBe null
        }

        "a truncated list of untracked entries still proves dirty without refreshing the index" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Many untracked files")
            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            val administrative = f.storage.namespaceGitDirectory(f.namespaceId).worktreeRegistration(root.id)
            val indexBefore = Files.readAllBytes(administrative.resolve(GitLayout.INDEX_FILE))
            Files.setLastModifiedTime(path.resolve("README.md"), FileTime.fromMillis(System.currentTimeMillis() + 10_000))
            repeat(30) { Files.writeString(path.resolve("untracked-file-with-a-long-name-$it.txt"), "test") }
            val smallOutput = GitCommandRunner(gitProperties.copy(maxOutputChars = 512))

            val observed = statusService(f, configured, mockk(), smallOutput).let { it.summary(it.refresh(ready, path))!! }

            observed.dirty shouldBe true
            observed.error shouldBe null
            Files.readAllBytes(administrative.resolve(GitLayout.INDEX_FILE)).toList() shouldBe indexBefore.toList()
        }

        "provider exception messages and causes never enter the persisted status" {
            val secret = "synthetic-git-token"
            val hosting = mockk<GitHostingProvider> {
                every { inspect(any(), any(), any()) } throws IllegalArgumentException(
                    "invalid header value: Bearer $secret", IllegalStateException(secret),
                )
            }
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Provider failure")
            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            rawGit(path, "switch", "-c", "agent-branch")
            val status = statusService(f, configured, hosting)

            val saved = status.refresh(ready, path)

            val observed = status.summary(saved)!!
            observed.prState shouldBe "UNKNOWN"
            observed.error shouldBe "Git status unavailable. Check repository access and service account settings."
            saved.summaryJson!! shouldNotContain secret
        }

        "slow forge observation does not hold admission lock or overwrite a concurrent lifecycle transition" {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val hosting = mockk<GitHostingProvider> {
                every { inspect(any(), any(), any()) } answers {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    GitWorkspaceSummary(prState = "NONE")
                }
            }
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Slow forge")
            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            rawGit(path, "switch", "-c", "agent-branch")
            val status = statusService(f, configured, hosting)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<CaseResourceBinding> { status.refresh(ready, path) }
                entered.await(10, TimeUnit.SECONDS) shouldBe true
                WorkspaceLifecycleLocks.tryWithRoot(root.id, onBusy = { false }) { true } shouldBe true
                WorkspaceLifecycleLocks.withRoot(root.id) {
                    f.bindings.markStatus(ready.id, CaseResourceStatus.DELETING)
                }
                release.countDown()
                future.get(10, TimeUnit.SECONDS).status shouldBe CaseResourceStatus.DELETING
                f.bindings.findByRootCaseId(root.id)!!.status shouldBe CaseResourceStatus.DELETING
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    })
