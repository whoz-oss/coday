package io.whozoss.agentos.git

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/** Projects workspace preparation without inspecting Git or contacting a hosting provider. */
@Service
@ConditionalOnProperty(prefix = "agentos.git.workspaces", name = ["enabled"], havingValue = "true")
class GitWorkspaceStatusService {
    fun view(root: GitExchangeRoot): CaseWorkspaceView {
        val binding = root.binding ?: return CaseWorkspaceView(equipped = false)
        return CaseWorkspaceView(
            equipped = true,
            rootCaseId = binding.rootCaseId,
            status = binding.status,
            failureReason = binding.failureReason,
            cleanupReason = binding.cleanupReason,
        )
    }
}
