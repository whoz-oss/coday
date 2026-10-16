package io.whozoss.factory.workspace

import io.whozoss.factory.persistence.TenantScope
import java.nio.file.Path
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Explicitly binds the repo root of a run's `code` steps to the run's Git
 * worktree.
 *
 * Sharing the agent case family is NOT enough to guarantee that builds and
 * verifications run against the right repository: the `repoRoot` handed to a
 * `code` step could silently be a stale namespace repo root. This resolver makes
 * the binding explicit and centralized:
 *
 *  - when the run has a workspace, the waiter proves it `READY` and its exact
 *    worktree path becomes the `repoRoot`;
 *  - when the run has NO workspace (no environment was requested), the
 *    caller-provided fallback is kept — this is the documented, explicit
 *    no-worktree case, never a silent switch.
 *
 * It never picks another directory on its own: the waiter either returns the
 * proven worktree or throws [WorkspaceProvisioningException].
 */
@Service
class RunWorktreeResolver(
    private val waiter: WorkspaceStateWaiter,
) {

    private val logger = KotlinLogging.logger {}

    /**
     * The `repoRoot` a code step must run against for [workflowId]: the run's
     * validated worktree, or [fallbackRepoRoot] when no workspace is requested.
     */
    fun resolve(scope: TenantScope, workflowId: String, fallbackRepoRoot: Path): Path {
        val facts = waiter.awaitWorkspaceReady(scope, workflowId) ?: return fallbackRepoRoot
        val worktreeRoot = Path.of(facts.worktreePath)
        if (worktreeRoot != fallbackRepoRoot) {
            logger.info {
                "Run worktree of workflow '$workflowId' resolves code steps to '$worktreeRoot' " +
                    "(namespace repo root fallback was '$fallbackRepoRoot')"
            }
        }
        return worktreeRoot
    }
}
