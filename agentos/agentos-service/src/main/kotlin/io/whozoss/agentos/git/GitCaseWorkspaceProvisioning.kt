package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.ObjectMapper

import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseWorkspaceProvisioning
import mu.KLogging
import org.springframework.stereotype.Component

/**
 * Decides, at creation time, whether a case starts a family that owns a Git workspace.
 *
 * Only a **root** case can. A sub-case never allocates anything: it resolves through its root, so
 * the whole family shares one branch and one worktree. That is also why the decision is taken here
 * and persisted as a binding rather than re-derived later from the namespace's configuration —
 * re-deriving would make the automation switch retroactive in both directions.
 *
 * This records the intent only. Cloning, worktree creation and setup happen in
 * [CaseWorktreeProvisioner], driven separately, so creating a case stays fast and a provisioning
 * failure never turns into a failure to create the conversation.
 */
@Component
class GitCaseWorkspaceProvisioning(
    private val associationService: GitRepositoryAssociationService,
    private val bindingService: CaseResourceBindingService,
    private val objectMapper: ObjectMapper,
) : CaseWorkspaceProvisioning {
    override fun onCaseCreated(case: Case) {
        if (case.parentCaseId != null) return

        // Invalid configured Git must not silently produce an ordinary family.
        val settings = associationService.findSettings(case.namespaceId) ?: return

        if (!settings.autoWorktreeForRootCases) return

        bindingService.findByRootCaseId(case.id)?.let { return }

        val binding =
            bindingService.create(
                CaseResourceBinding(
                    rootCaseId = case.id,
                    namespaceId = case.namespaceId,
                    integrationConfigId = settings.configId,
                    status = CaseResourceStatus.REQUESTED,
                    settingsJson = objectMapper.writeValueAsString(settings),
                ),
            )
        logger.info { "Case ${case.id} equipped with workspace ${binding.id} (title '${case.title}')" }
    }

    companion object : KLogging()
}
