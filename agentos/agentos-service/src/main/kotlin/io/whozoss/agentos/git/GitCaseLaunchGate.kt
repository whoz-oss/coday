package io.whozoss.agentos.git

import io.whozoss.agentos.caseFlow.CaseLaunchGate
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
 * explicit recovery commands remain available through the workspace API.
 */
@Component
class GitCaseLaunchGate(
    private val exchangeRootResolver: ExchangeRootResolver,
) : CaseLaunchGate {
    override fun canLaunch(caseId: UUID): Boolean {
        val resolved =
            try {
                exchangeRootResolver.resolve(caseId)
            } catch (e: Exception) {
                logger.error(e) { "Could not resolve the workspace of case $caseId; refusing execution" }
                return false
            }

        if (resolved.isPending) {
            logger.info { "Case $caseId is waiting for workspace ${resolved.binding?.id} (${resolved.binding?.status})" }
            return false
        }
        return resolved.isUsable
    }

    companion object : KLogging()
}
