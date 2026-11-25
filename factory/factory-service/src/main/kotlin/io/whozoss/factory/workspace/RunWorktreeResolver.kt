package io.whozoss.factory.workspace

import io.whozoss.factory.environment.port.BoundedGitCommandRunner
import io.whozoss.factory.environment.port.GitCommandRunner
import io.whozoss.factory.persistence.TenantScope
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.stereotype.Service

data class CodeWorkspaceBinding(
    val root: Path,
    val provenance: String,
    val ownerId: String,
    val branch: String? = null,
    val head: String? = null,
    val caseId: String? = null,
    val expectedBranch: String? = null,
) {
    fun facts(): Map<String, Any?> = mapOf(
        "cwd" to root.toString(), "workspaceProvenance" to provenance,
        "workspaceOwnerId" to ownerId, "workspaceBranch" to branch, "observedHead" to head, "workspaceCaseId" to caseId,
        "expectedBranch" to expectedBranch,
        "branchExpectationSource" to when {
            provenance == "workspace-disabled" -> "checks-disabled"
            expectedBranch == null -> "unavailable-attached-only"
            else -> "factory-branch-preparation"
        },
        "workspaceChecksEnabled" to (provenance != "workspace-disabled"),
    )
}

/** No directory discovery: every code step needs an environment unless explicitly disabled. */
@Service
class RunWorktreeResolver(
    private val waiter: WorkspaceStateWaiter,
    private val git: GitCommandRunner = BoundedGitCommandRunner(),
    private val properties: WorkspaceProperties = WorkspaceProperties(),
) {
    fun resolve(scope: TenantScope, workflowId: String, fallbackRepoRoot: Path): Path =
        resolveBinding(scope, workflowId, fallbackRepoRoot).root

    fun resolveBinding(
        scope: TenantScope,
        workflowId: String,
        fallbackRepoRoot: Path,
        namespaceId: String? = null,
        requireBranch: Boolean = true,
        expectedCaseId: String? = null,
        branchExpectation: (String?) -> String? = { null },
    ): CodeWorkspaceBinding {
        if (!properties.enabled) return fallback(workflowId, fallbackRepoRoot, "workspace-disabled")
        val facts = checkNotNull(waiter.awaitWorkspaceReady(scope, workflowId)) {
            "CODE_WORKSPACE_REQUIRED: owner '$workflowId' has no environment; refusing submission fallback '$fallbackRepoRoot'."
        }
        check(expectedCaseId == null || facts.caseId == expectedCaseId) { "CODE_WORKSPACE_INVALID: root case mismatch for '$workflowId'." }
        val path = Path.of(facts.worktreePath)
        check(path.isAbsolute && Files.isDirectory(path)) { "CODE_WORKSPACE_INVALID: unavailable absolute worktree '$path'." }
        val root = path.toRealPath()
        check(Files.exists(root.resolve(".git"))) { "CODE_WORKSPACE_INVALID: '$root' is not an exact Git root." }
        check(namespaceId == null || facts.namespaceId == namespaceId) { "CODE_WORKSPACE_INVALID: namespace mismatch for '$root'." }
        val top = read(root, listOf("rev-parse", "--show-toplevel"))
        check(Path.of(top).toRealPath() == root) { "CODE_WORKSPACE_INVALID: Git root differs from '$root'." }
        val head = read(root, listOf("rev-parse", "--verify", "HEAD"))
        check(SHA.matches(head)) { "CODE_WORKSPACE_INVALID: invalid HEAD at '$root'." }
        val branchResult = git.run(root, listOf("symbolic-ref", "--quiet", "--short", "HEAD"))
        val branch = branchResult.stdout.trim().takeIf { branchResult.exitCode == 0 }
        // Provisioning records an observation, often detached, not a durable branch policy.
        val expectedBranch = branchExpectation(facts.environmentId)?.takeIf { it.isNotBlank() }
        check(!requireBranch || (!branch.isNullOrBlank() && (expectedBranch == null || branch == expectedBranch))) {
            "CODE_WORKSPACE_BRANCH_MISMATCH: '$root' expected '${expectedBranch ?: "attached branch (no reliable expectation)"}', observed '$branch' at '$head'."
        }
        // baseCommit is a baseline, NOT the expected current HEAD: agents may have committed since provisioning.
        facts.baseCommit?.let { base ->
            check(SHA.matches(base) && git.run(root, listOf("merge-base", "--is-ancestor", base, head)).exitCode == 0) {
                "CODE_WORKSPACE_HEAD_MISMATCH: '$head' at '$root' does not descend from '$base'."
            }
        }
        return CodeWorkspaceBinding(root, "run-environment", workflowId, branch, head, facts.caseId, expectedBranch)
    }

    private fun fallback(owner: String, root: Path, source: String) =
        CodeWorkspaceBinding(root.toAbsolutePath().normalize(), source, owner)

    private fun read(root: Path, arguments: List<String>): String {
        val result = git.run(root, arguments)
        check(result.exitCode == 0) { "CODE_WORKSPACE_GIT_FAILED: cannot inspect '$root' (${arguments.first()})." }
        return result.stdout.trim()
    }

    private companion object { val SHA = Regex("^[0-9a-fA-F]{40}$") }
}
