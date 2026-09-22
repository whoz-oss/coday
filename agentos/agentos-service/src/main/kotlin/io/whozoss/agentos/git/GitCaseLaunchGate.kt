package io.whozoss.agentos.git

import io.whozoss.agentos.caseFlow.CaseLaunchGate
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.exception.ConflictException
import mu.KLogging
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Holds a run back while its family's workspace is still being prepared.
 *
 * Cloning a repository and installing its dependencies takes minutes. Starting a run before the
 * worktree exists would have the agent's tools resolve to a directory that is not there yet, so
 * the case waits instead — its message is already persisted, and the provisioning sweep resumes it
 * once the workspace is ready.
 *
 * Failed and removed resources also refuse execution. Their status and
 * preparation retry commands remain available through the workspace API.
 */
@Component
class GitCaseLaunchGate(
    private val exchangeRootResolver: GitExchangeRootResolver,
    private val caseRepository: CaseRepository,
) : CaseLaunchGate {
    override fun canLaunch(caseId: UUID): Boolean {
        // A lookup failure propagates. Answering "not ready" would park the turn of any case,
        // equipped or not, with nothing left to resume it; the case service retries the decision.
        val resolved = exchangeRootResolver.resolveGit(caseId)

        if (resolved.binding != null && caseRepository.findById(caseId)?.status?.isTerminal() != false) return false
        if (resolved.isPending) {
            logger.info { "Case $caseId is waiting for workspace ${resolved.binding?.id} (${resolved.binding?.status})" }
            return false
        }
        return resolved.isUsable
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
        val binding = exchangeRootResolver.resolveGit(caseId).binding ?: return
        if (binding.status in setOf(CaseResourceStatus.DELETING, CaseResourceStatus.REMOVED)) {
            throw ConflictException("The workspace is being removed or has been removed")
        }
        if (caseRepository.findById(caseId)?.status?.isTerminal() != false) {
            throw ConflictException("This case is closed and cannot accept new messages")
        }
    }

    companion object : KLogging()
}
