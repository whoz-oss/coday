package io.whozoss.agentos.git

import io.whozoss.agentos.caseFlow.CaseLaunchGate
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.LaunchDecision
import io.whozoss.agentos.exception.ConflictException
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Holds a run back while its family's workspace is still being prepared.
 *
 * Cloning a repository and installing its dependencies takes minutes. Starting a run before the
 * worktree exists would have the agent's tools resolve to a directory that is not there yet, so
 * the case waits instead. Its message is already persisted, and whatever prepares the workspace must
 * call [io.whozoss.agentos.caseFlow.CaseService.resumeIfPending] once it is ready. Nothing creates a
 * binding yet: worktree allocation, and the sweep that resumes held turns, come with the next change.
 *
 * Failed and removed resources also refuse execution.
 *
 * Installed only with `agentos.git.workspaces.enabled`: without it, runs start immediately.
 */
@Component
@ConditionalOnProperty(prefix = "agentos.git.workspaces", name = ["enabled"], havingValue = "true")
class GitCaseLaunchGate(
    private val exchangeRootResolver: GitExchangeRootResolver,
    private val caseRepository: CaseRepository,
) : CaseLaunchGate {
    override fun launchDecision(caseId: UUID): LaunchDecision {
        // A lookup failure propagates. Answering Wait would park the turn of any case,
        // equipped or not, with nothing left to resume it; the case service reports it instead.
        val resolved = exchangeRootResolver.resolveGit(caseId)

        // A terminal case with a binding must not run: the workspace may still exist but the
        // case itself is closed. Refuse rather than Wait so the caller cleans up immediately.
        if (resolved.binding != null && caseRepository.findById(caseId)?.status?.isTerminal() != false) {
            return LaunchDecision.Refuse("Case $caseId is in a terminal status and cannot run")
        }

        return when {
            resolved.isPending -> {
                val status = resolved.binding?.status
                logger.info { "Case $caseId is waiting for workspace ${resolved.binding?.id} ($status)" }
                LaunchDecision.Wait("Workspace ${resolved.binding?.id} is $status")
            }
            resolved.isUsable -> LaunchDecision.Admit
            else -> {
                // FAILED, DELETING, REMOVED, or any other non-usable, non-pending state.
                // These will never become usable on their own: refuse immediately.
                val status = resolved.binding?.status
                LaunchDecision.Refuse("Workspace ${resolved.binding?.id} is $status and cannot be used")
            }
        }
    }

    override fun withAdmission(caseId: UUID, onAvailable: () -> Unit, action: () -> Unit) {
        val rootId = exchangeRootResolver.resolveGit(caseId).binding?.rootCaseId
        if (rootId == null) action()
        else WorkspaceLifecycleLocks.tryWithRoot(
            rootId,
            onBusy = { WorkspaceLifecycleLocks.whenAvailable(rootId, caseId, onAvailable) },
            action = action,
        )
    }

    override fun keepOpenOnShutdown(caseId: UUID): Boolean =
        exchangeRootResolver.resolveGit(caseId).binding != null

    override fun requireAccepting(caseId: UUID) {
        exchangeRootResolver.resolveGit(caseId).binding?.let { binding ->
            if (binding.status.isRemovalStarted) {
                throw ConflictException("The workspace is being removed or has been removed")
            }
            if (caseRepository.findById(caseId)?.status?.isTerminal() != false) {
                throw ConflictException("This case is closed and cannot accept new messages")
            }
        }
    }

    companion object : KLogging()
}
