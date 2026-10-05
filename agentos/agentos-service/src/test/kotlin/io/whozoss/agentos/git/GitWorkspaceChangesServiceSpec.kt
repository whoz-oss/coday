package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import java.nio.file.Path
import java.util.UUID

/**
 * Unit tests for [GitWorkspaceChangesService]. No real Git process is spawned: all Git
 * observations are mocked. The suite focuses on the branching logic that decides what to include
 * in [ExchangeEnvironment] and on the graceful-degradation contract that must never let a Git
 * inspection failure propagate as an HTTP 500.
 */
class GitWorkspaceChangesServiceSpec : StringSpec({

    fun makeBinding(
        status: CaseResourceStatus,
        branchName: String? = "feature/work",
        baseSha: String? = "abc123",
        settings: GitRepositorySettings? = null,
    ) = CaseResourceBinding(
        rootCaseId = UUID.randomUUID(),
        namespaceId = UUID.randomUUID(),
        integrationConfigId = UUID.randomUUID(),
        status = status,
        branchName = branchName,
        baseSha = baseSha,
        settings = settings,
    )

    fun makeRoot(
        path: Path = Path.of("/tmp/exchange/case-1"),
        binding: CaseResourceBinding? = null,
        ownerCaseId: UUID = UUID.randomUUID(),
    ) = GitExchangeRoot(path = path, binding = binding, ownerCaseId = ownerCaseId)

    fun makeSettings(mainBranch: String = "main") = GitRepositorySettings(
        configId = UUID.randomUUID(),
        namespaceId = UUID.randomUUID(),
        repositoryUrl = "https://example.invalid/repo.git",
        mainBranch = mainBranch,
        serviceAuthSettingId = UUID.randomUUID(),
        autoWorktreeForRootCases = true,
        setupCommand = null,
    )

    val summary = GitWorkspaceSummary(prState = "OPEN", branchState = "PUSHED")

    // (a) A case with no binding at all has no Git workspace.
    // environment() must return immediately with equipped=false and never touch ExchangeGitDiff:
    // calling diffs.branch() or diffs.changes() on an unequipped case would be a bug.
    "a case without a binding returns an unequipped environment and never touches ExchangeGitDiff" {
        val status = mockk<GitWorkspaceStatusService>()
        val diffs = mockk<ExchangeGitDiff>()
        val service = GitWorkspaceChangesService(status, diffs)
        val root = makeRoot(binding = null)

        val result = service.environment(root)

        result shouldBe ExchangeEnvironment(equipped = false)
        verify(exactly = 0) { diffs.branch(any()) }
        verify(exactly = 0) { diffs.changes(any()) }
    }

    // (b) A non-READY binding signals that the workspace is known but not yet operational.
    // environment() must return the status and path so the UI can show a progress indicator,
    // but must never attempt any Git inspection: the worktree directory may not even exist yet.
    "a PREPARING binding returns equipped=true with status and path but no Git inspection" {
        val binding = makeBinding(CaseResourceStatus.PREPARING)
        val root = makeRoot(binding = binding)
        val status = mockk<GitWorkspaceStatusService> {
            every { summary(binding) } returns summary
        }
        val diffs = mockk<ExchangeGitDiff>()
        val service = GitWorkspaceChangesService(status, diffs)

        val result = service.environment(root)

        result.equipped shouldBe true
        result.status shouldBe "PREPARING"
        result.path shouldBe root.repositoryPath.toAbsolutePath().normalize().toString()
        result.git shouldBe summary
        result.branch shouldBe null
        result.changes shouldBe null
        verify(exactly = 0) { diffs.branch(any()) }
    }

    "a FAILED binding returns equipped=true with status and path but no Git inspection" {
        val binding = makeBinding(CaseResourceStatus.FAILED)
        val root = makeRoot(binding = binding)
        val status = mockk<GitWorkspaceStatusService> {
            every { summary(binding) } returns summary
        }
        val diffs = mockk<ExchangeGitDiff>()
        val service = GitWorkspaceChangesService(status, diffs)

        val result = service.environment(root)

        result.equipped shouldBe true
        result.status shouldBe "FAILED"
        result.git shouldBe summary
        verify(exactly = 0) { diffs.branch(any()) }
    }

    // (c) When the binding is READY and the observed branch matches binding.branchName,
    // the git summary from the status service is kept in the result.
    // This is the normal case: the forge monitor's PR information is current.
    "a READY binding where the observed branch matches branchName keeps the git summary" {
        val branchName = "feature/current"
        val binding = makeBinding(CaseResourceStatus.READY, branchName = branchName)
        val settings = makeSettings()
        val root = makeRoot(binding = binding)
        val changes = ExchangeGitChanges(base = "abc", files = emptyList())
        val status = mockk<GitWorkspaceStatusService> {
            every { summary(binding) } returns summary
            every { worktreeGitDir(binding) } returns Path.of("/tmp/worktrees/${binding.rootCaseId}")
            every { commonGitDir(binding) } returns Path.of("/tmp/common.git")
            every { settings(binding) } returns settings
        }
        val diffs = mockk<ExchangeGitDiff> {
            every { branch(any()) } returns branchName
            every { changes(any()) } returns changes
        }
        val service = GitWorkspaceChangesService(status, diffs)

        val result = service.environment(root)

        // The git summary is preserved because the branch is current.
        result.branch shouldBe branchName
        result.changes shouldBe changes
        result.git shouldBe summary
    }

    // (d) When the observed branch differs from binding.branchName, the git summary must be
    // set to null. The forge monitor is asynchronous: its summary was computed for the old branch
    // and must never be shown as if it described the current HEAD. This is the subtlest invariant
    // in the service and the most likely to be broken silently by a refactor.
    "a READY binding where the observed branch differs from branchName nulls out the git summary" {
        val oldBranch = "feature/old"
        val newBranch = "feature/new"
        // The binding still records the branch the forge monitor last saw.
        val binding = makeBinding(CaseResourceStatus.READY, branchName = oldBranch)
        val settings = makeSettings()
        val root = makeRoot(binding = binding)
        val changes = ExchangeGitChanges(base = "abc", files = emptyList())
        val status = mockk<GitWorkspaceStatusService> {
            every { summary(binding) } returns summary
            every { worktreeGitDir(binding) } returns Path.of("/tmp/worktrees/${binding.rootCaseId}")
            every { commonGitDir(binding) } returns Path.of("/tmp/common.git")
            every { settings(binding) } returns settings
        }
        val diffs = mockk<ExchangeGitDiff> {
            // Git sees the new branch, not the one the binding remembers.
            every { branch(any()) } returns newBranch
            every { changes(any()) } returns changes
        }
        val service = GitWorkspaceChangesService(status, diffs)

        val result = service.environment(root)

        result.branch shouldBe newBranch
        result.changes shouldBe changes
        // The PR and forge data from summary belong to oldBranch and must not leak onto newBranch.
        result.git shouldBe null
    }

    // (e) A detached HEAD has branchName=null on the binding (the forge monitor recorded null)
    // and diffs.branch() also returns null. Both sides of the equality are null, so null == null
    // is true and the git summary is preserved. This is counter-intuitive: one might expect the
    // null check to strip the summary, but the contract is "same state as last observed".
    "a detached HEAD where binding.branchName is null keeps the git summary" {
        // Both the binding and the live observation agree: detached HEAD, no branch.
        val binding = makeBinding(CaseResourceStatus.READY, branchName = null)
        val settings = makeSettings()
        val root = makeRoot(binding = binding)
        val changes = ExchangeGitChanges(base = "deadbeef", files = emptyList())
        val status = mockk<GitWorkspaceStatusService> {
            every { summary(binding) } returns summary
            every { worktreeGitDir(binding) } returns Path.of("/tmp/worktrees/${binding.rootCaseId}")
            every { commonGitDir(binding) } returns Path.of("/tmp/common.git")
            every { settings(binding) } returns settings
        }
        val diffs = mockk<ExchangeGitDiff> {
            every { branch(any()) } returns null
            every { changes(any()) } returns changes
        }
        val service = GitWorkspaceChangesService(status, diffs)

        val result = service.environment(root)

        result.branch shouldBe null
        result.changes shouldBe changes
        // null == null: the observation is consistent with the binding, so the summary is kept.
        result.git shouldBe summary
    }

    // (f) diffs.branch() may fail (network, corrupted repo, concurrent write…). The service
    // must swallow the exception and return a degraded but valid response. Without this guarantee
    // the endpoint becomes HTTP 500 whenever Git is temporarily unavailable, which is unacceptable.
    "when diffs.branch() throws, environment() returns a degraded response without propagating" {
        val binding = makeBinding(CaseResourceStatus.READY, branchName = "feature/work")
        val settings = makeSettings()
        val root = makeRoot(binding = binding)
        val status = mockk<GitWorkspaceStatusService> {
            every { summary(binding) } returns summary
            every { worktreeGitDir(binding) } returns Path.of("/tmp/worktrees/${binding.rootCaseId}")
            every { commonGitDir(binding) } returns Path.of("/tmp/common.git")
            every { settings(binding) } returns settings
        }
        val diffs = mockk<ExchangeGitDiff> {
            every { branch(any()) } throws RuntimeException("git process failed")
        }
        val service = GitWorkspaceChangesService(status, diffs)

        val result = service.environment(root)

        // The endpoint must still return 200 with a user-readable error, not throw.
        result.equipped shouldBe true
        result.error shouldContain "Cannot inspect Git changes"
        result.branch shouldBe null
        result.changes shouldBe null
    }

    // (g) diffs.changes() may also fail. Same graceful-degradation contract as (f).
    "when diffs.changes() throws, environment() returns a degraded response without propagating" {
        val binding = makeBinding(CaseResourceStatus.READY, branchName = "feature/work")
        val settings = makeSettings()
        val root = makeRoot(binding = binding)
        val status = mockk<GitWorkspaceStatusService> {
            every { summary(binding) } returns summary
            every { worktreeGitDir(binding) } returns Path.of("/tmp/worktrees/${binding.rootCaseId}")
            every { commonGitDir(binding) } returns Path.of("/tmp/common.git")
            every { settings(binding) } returns settings
        }
        val diffs = mockk<ExchangeGitDiff> {
            every { branch(any()) } returns "feature/work"
            every { changes(any()) } throws RuntimeException("git diff failed")
        }
        val service = GitWorkspaceChangesService(status, diffs)

        val result = service.environment(root)

        result.equipped shouldBe true
        result.error shouldContain "Cannot inspect Git changes"
        result.branch shouldBe null
        result.changes shouldBe null
    }

    // (h) diff() on a root with no binding must throw ResourceNotFoundException immediately.
    // There is no Git repository to diff against, and 404 is the correct HTTP response.
    "diff() without a binding throws ResourceNotFoundException" {
        val status = mockk<GitWorkspaceStatusService>()
        val diffs = mockk<ExchangeGitDiff>()
        val service = GitWorkspaceChangesService(status, diffs)
        val root = makeRoot(binding = null)

        shouldThrow<ResourceNotFoundException> { service.diff(root, "README.md") }
    }

    // (i) diff() on a non-READY binding must throw ConflictException so the caller receives 409.
    // The worktree may not exist yet; attempting to read from it would produce confusing errors.
    "diff() with a non-READY binding throws ConflictException" {
        val binding = makeBinding(CaseResourceStatus.PREPARING)
        val root = makeRoot(binding = binding)
        val status = mockk<GitWorkspaceStatusService>()
        val diffs = mockk<ExchangeGitDiff>()
        val service = GitWorkspaceChangesService(status, diffs)

        shouldThrow<ConflictException> { service.diff(root, "README.md") }
    }

    // (j) diff() with a READY binding must delegate to diffs.file() with the correct ExchangeGitTarget.
    // The target must use the normalized absolute repositoryPath, the correct gitDir paths from
    // the status service, the mainBranch from settings, and the baseSha from the binding.
    // We capture the target with a slot to assert each field individually.
    "diff() with a READY binding delegates to diffs.file() with the correct ExchangeGitTarget" {
        val baseSha = "deadbeef1234"
        val binding = makeBinding(CaseResourceStatus.READY, baseSha = baseSha)
        val settings = makeSettings(mainBranch = "main")
        val root = makeRoot(binding = binding)
        val expectedPath = root.repositoryPath.toAbsolutePath().normalize()
        val worktreeGitDir = Path.of("/tmp/worktrees/${binding.rootCaseId}")
        val commonGitDir = Path.of("/tmp/common.git")
        val expectedDiff = ExchangeFileDiff(patch = "@@ -1 +1 @@\n-old\n+new")

        val status = mockk<GitWorkspaceStatusService> {
            every { worktreeGitDir(binding) } returns worktreeGitDir
            every { commonGitDir(binding) } returns commonGitDir
            every { settings(binding) } returns settings
        }
        val capturedTarget = slot<ExchangeGitTarget>()
        val diffs = mockk<ExchangeGitDiff> {
            every { file(capture(capturedTarget), "README.md") } returns expectedDiff
        }
        val service = GitWorkspaceChangesService(status, diffs)

        val result = service.diff(root, "README.md")

        result shouldBe expectedDiff
        capturedTarget.captured.path shouldBe expectedPath
        capturedTarget.captured.mainBranch shouldBe settings.mainBranch
        capturedTarget.captured.fallbackBase shouldBe baseSha
        capturedTarget.captured.gitDir shouldBe worktreeGitDir
        capturedTarget.captured.commonDir shouldBe commonGitDir
    }
})
