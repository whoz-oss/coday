package io.whozoss.agentos.git

import org.springframework.stereotype.Service

/** Projects workspace preparation without inspecting Git or contacting a hosting provider. */
@Service
class GitWorkspaceStatusService {
    fun view(root: ResolvedExchangeRoot): CaseWorkspaceView {
        val binding = root.binding ?: return CaseWorkspaceView(equipped = false)
        return CaseWorkspaceView(
            equipped = true,
            rootCaseId = binding.rootCaseId,
            status = binding.status.name,
            failureReason = binding.failureReason,
            cleanupReason = binding.cleanupReason,
        )
    }
}
