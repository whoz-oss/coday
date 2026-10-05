package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.ObjectMapper

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
    private val objectMapper: ObjectMapper,
    private val gitAvailability: GitAvailability,
    private val worker: ObjectProvider<CaseWorkspaceWorker>,
) : CaseWorkspaceProvisioning {
    override fun <T> aroundCreation(case: Case, action: () -> T): T =
        // Only a namespace that equips new families has settings to freeze: creations elsewhere never wait.
        if (case.parentCaseId == null && associationService.automationEnabled(case.namespaceId)) {
            WorkspaceLifecycleLocks.withNamespace(case.namespaceId, action)
        } else {
            action()
        }

    override fun onCaseCreated(case: Case) {
        // Equip only a creation that [aroundCreation] serialized with settings saves. Taking the lock
        // now, after the case write, could deadlock with a save waiting for that write's database lock.
        if (case.parentCaseId == null && WorkspaceLifecycleLocks.holdsNamespace(case.namespaceId)) allocate(case)
    }

    private fun allocate(case: Case) {
        equippingSettings(case)?.let { settings ->
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

    companion object : KLogging()
}
