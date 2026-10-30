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
 * The namespace's automation decides; the creator can only decline it, never equip a root case the
 * namespace would not. A creator who requires the worktree gets it, or the creation is refused:
 * the case is never silently made without it.
 *
 * This records the intent only. Cloning, worktree creation and setup happen in
 * [CaseWorktreeProvisioner], driven separately, so creating a case stays fast and a failed
 * preparation never turns into a failure to create the conversation. Invalid namespace settings do
 * refuse a root case that automation should equip: the family could never be equipped later, and
 * only a namespace admin can fix them.
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
    override fun <T> aroundCreation(case: Case, worktree: Boolean?, action: () -> T): T {
        if (case.parentCaseId != null || worktree == false) return action()
        val automation = associationService.automationEnabled(case.namespaceId)
        if (worktree == true) requireWorktreePossible(automation)
        // Only a namespace that equips new families has settings to freeze: creations elsewhere never wait.
        return if (automation) WorkspaceLifecycleLocks.withNamespace(case.namespaceId, action) else action()
    }

    override fun onCaseCreated(case: Case, worktree: Boolean?) {
        if (case.parentCaseId != null || worktree == false) return
        // Equip only a creation that [aroundCreation] serialized with settings saves. Taking the lock
        // now, after the case write, could deadlock with a save waiting for that write's database lock.
        if (WorkspaceLifecycleLocks.holdsNamespace(case.namespaceId)) {
            allocate(case, required = worktree == true)
        } else {
            check(worktree != true) { "Case ${case.id} requires a worktree but was not created through aroundCreation" }
        }
    }

    /** Refuses a required worktree before the case is written when this instance or namespace cannot create it. */
    private fun requireWorktreePossible(automation: Boolean) {
        val reason =
            when {
                !gitAvailability.isAvailable() -> "Git is not available on this instance"
                worker.getIfAvailable() == null -> "the Git worker is disabled on this instance"
                !automation -> NAMESPACE_WITHOUT_WORKTREES
                else -> null
            }
        reason?.let { throw ConflictException("$WORKTREE_REFUSED: $it") }
    }

    private fun allocate(case: Case, required: Boolean) {
        val settings = equippingSettings(case)
        // A settings save this creation waited for may have turned automation off.
        if (settings == null && required) throw ConflictException("$WORKTREE_REFUSED: $NAMESPACE_WITHOUT_WORKTREES")
        settings?.let {
            val binding =
                bindingService.create(
                    CaseResourceBinding(
                        rootCaseId = case.id,
                        namespaceId = case.namespaceId,
                        integrationConfigId = it.configId,
                        status = CaseResourceStatus.REQUESTED,
                        settings = it,
                    ),
                )
            logger.info { "Case ${case.id} equipped with workspace ${binding.id} (title '${case.title}')" }
        }
    }

    /** The settings a new root case is equipped with, or null when this instance or namespace equips nothing. */
    private fun equippingSettings(case: Case): GitRepositorySettings? =
        when {
            // Without the GIT plugin no new family is equipped; families equipped earlier keep working.
            !gitAvailability.isAvailable() -> null
            worker.getIfAvailable() == null -> {
                // Allocation only runs where automation is on: this case would have been equipped.
                logger.warn { "Case ${case.id} not equipped: Git workspaces are enabled but the Git worker is disabled" }
                null
            }
            else -> automaticSettings(case)
        }

    /** The settings automation equips [case] with. Invalid ones are the admin's to fix, not the caller's. */
    private fun automaticSettings(case: Case): GitRepositorySettings? =
        try {
            associationService.findAutomaticSettings(case.namespaceId)
        } catch (e: BadRequestException) {
            throw ConflictException(
                "The namespace Git settings are invalid (${e.message}): " +
                    "a namespace admin must fix them before new conversations can start",
                e,
            )
        }

    companion object : KLogging() {
        private const val WORKTREE_REFUSED = "Cannot create a worktree for this case"
        private const val NAMESPACE_WITHOUT_WORKTREES = "its namespace does not create worktrees"
    }
}
