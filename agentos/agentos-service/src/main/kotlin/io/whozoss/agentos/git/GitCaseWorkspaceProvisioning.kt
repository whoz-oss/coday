package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.ObjectMapper

import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseWorkspaceProvisioning
import mu.KLogging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
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
 *
 * Installed only with `agentos.git.workspaces.enabled`. No family is equipped while the worker is
 * disabled: nothing would prepare its workspace, and its turns would wait forever.
 */
@Component
@ConditionalOnProperty(prefix = "agentos.git.workspaces", name = ["enabled"], havingValue = "true")
class GitCaseWorkspaceProvisioning(
    private val associationService: GitRepositoryAssociationService,
    private val bindingService: CaseResourceBindingService,
    private val objectMapper: ObjectMapper,
    private val gitAvailability: GitAvailability,
    private val worker: ObjectProvider<CaseWorkspaceWorker>,
) : CaseWorkspaceProvisioning {
    override fun <T> aroundCreation(case: Case, action: () -> T): T =
        if (case.parentCaseId == null) WorkspaceLifecycleLocks.withNamespace(case.namespaceId, action) else action()

    override fun onCaseCreated(case: Case) {
        if (case.parentCaseId != null) return
        WorkspaceLifecycleLocks.withNamespace(case.namespaceId) { allocate(case) }
    }

    private fun allocate(case: Case) {
        // Without the GIT plugin no new family is equipped; families equipped earlier keep working.
        if (!gitAvailability.isAvailable()) return
        if (worker.getIfAvailable() == null) {
            logger.warn { "Case ${case.id} not equipped: Git workspaces are enabled but the Git worker is disabled" }
            return
        }
        // Disabled automation remains independent of Git validation and availability.
        val settings = associationService.findAutomaticSettings(case.namespaceId) ?: return

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
