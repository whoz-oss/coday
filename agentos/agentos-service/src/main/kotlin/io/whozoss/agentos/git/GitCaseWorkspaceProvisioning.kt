package io.whozoss.agentos.git

import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseWorkspaceProvisioning
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
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
 * The creator may decide for the root case: a declined worktree is never created, and a requested
 * one is created even when automation is off, or the creation is refused, never silently made
 * without it. Without a choice, the namespace's automation decides.
 *
 * This records the intent only. Cloning, worktree creation and setup happen in
 * [CaseWorktreeProvisioner], driven separately, so creating a case stays fast and a failed
 * preparation never turns into a failure to create the conversation. Invalid namespace settings do
 * refuse a root case that should be equipped: the family could never be equipped later, and only a
 * namespace admin can fix them.
 *
 * Installed only with `agentos.git.workspaces.enabled`. No family is equipped while the worker is
 * disabled: nothing would prepare its workspace, and its turns would wait forever.
 */
@Component
@ConditionalOnProperty(prefix = "agentos.git.workspaces", name = ["enabled"], havingValue = "true")
class GitCaseWorkspaceProvisioning(
    private val associationService: GitRepositoryAssociationService,
    private val bindingService: CaseResourceBindingService,
    private val gitAvailability: GitAvailability,
    private val worker: ObjectProvider<CaseWorkspaceWorker>,
) : CaseWorkspaceProvisioning {
    override fun <T> aroundCreation(case: Case, worktree: Boolean?, action: () -> T): T =
        // Only a root case that gets a worktree has settings to freeze: creations elsewhere never wait.
        if (case.parentCaseId == null && (worktree ?: associationService.automationEnabled(case.namespaceId))) {
            WorkspaceLifecycleLocks.withNamespace(case.namespaceId, action)
        } else {
            action()
        }

    override fun onCaseCreated(case: Case, worktree: Boolean?) {
        // Equip only a creation that [aroundCreation] serialized with settings saves. Taking the lock
        // now, after the case write, could deadlock with a save waiting for that write's database lock.
        if (case.parentCaseId == null && worktree != false && WorkspaceLifecycleLocks.holdsNamespace(case.namespaceId)) {
            allocate(case, requested = worktree == true)
        }
    }

    private fun allocate(case: Case, requested: Boolean) {
        equippingSettings(case, requested)?.let { settings ->
            val binding =
                bindingService.create(
                    CaseResourceBinding(
                        rootCaseId = case.id,
                        namespaceId = case.namespaceId,
                        integrationConfigId = settings.configId,
                        status = CaseResourceStatus.REQUESTED,
                        settings = settings,
                    ),
                )
            logger.info { "Case ${case.id} equipped with workspace ${binding.id} (title '${case.title}')" }
        }
    }

    /** The settings a new root case is equipped with, or null when this instance or namespace equips nothing. */
    private fun equippingSettings(case: Case, requested: Boolean): GitRepositorySettings? =
        when {
            // Without the GIT plugin no new family is equipped; families equipped earlier keep working.
            !gitAvailability.isAvailable() -> {
                refuseIfRequested(requested, "Git is not available on this instance")
                null
            }
            worker.getIfAvailable() == null -> {
                refuseIfRequested(requested, "the Git worker is disabled on this instance")
                // Allocation only runs where automation is on: this case would have been equipped.
                logger.warn { "Case ${case.id} not equipped: Git workspaces are enabled but the Git worker is disabled" }
                null
            }
            else -> namespaceSettings(case, requested)
        }

    /**
     * The settings [case] is equipped with: the namespace's own when the creator asked for a worktree,
     * otherwise those of its automation. Invalid ones are the admin's to fix, not the caller's.
     */
    private fun namespaceSettings(case: Case, requested: Boolean): GitRepositorySettings? =
        try {
            if (requested) {
                associationService.findSettings(case.namespaceId)
                    ?: throw ConflictException("Cannot create a worktree for this case: its namespace has no Git repository")
            } else {
                associationService.findAutomaticSettings(case.namespaceId)
            }
        } catch (e: BadRequestException) {
            throw ConflictException(
                "The namespace Git settings are invalid (${e.message}): " +
                    "a namespace admin must fix them before new conversations can start",
                e,
            )
        }

    /** A requested worktree is created or the creation refused: the case is never made silently without it. */
    private fun refuseIfRequested(requested: Boolean, reason: String) {
        if (requested) throw ConflictException("Cannot create a worktree for this case: $reason")
    }

    companion object : KLogging()
}
