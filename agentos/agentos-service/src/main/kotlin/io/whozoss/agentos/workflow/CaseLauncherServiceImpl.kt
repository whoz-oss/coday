package io.whozoss.agentos.workflow

import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Standalone implementation of [CaseLauncherService] that creates and starts a case on behalf of a user.
 *
 * Extracted from [io.whozoss.agentos.caseFlow.CaseServiceImpl] to ensure that
 * [CaseService.create] is called through the Spring proxy and therefore honours its
 * `@Transactional` annotation. When [CaseServiceImpl] implemented [CaseLauncherService] itself,
 * `launchCase` called `this.create()` — a direct (non-proxied) call that silently bypassed
 * the transaction boundary.
 *
 * This bean is injected into [io.whozoss.agentos.agent.AgentExecutionContext] at run time
 * by [io.whozoss.agentos.caseFlow.CaseServiceImpl.runAgent], keeping the same cycle-free
 * design as [io.whozoss.agentos.delegation.SubCaseManager]:
 *
 *   CaseService → AgentService → CaseLauncherImpl → CaseService (via proxy — no cycle)
 *
 * [CaseService] must be injected lazily or via `@Lazy` if a circular dependency is detected
 * at startup; in practice Spring resolves it cleanly because `CaseLauncherImpl` does not
 * appear in `CaseServiceImpl`'s constructor.
 */
@Service
class CaseLauncherServiceImpl(
    @Lazy private val caseService: CaseService,
    private val permissionService: PermissionService,
    private val userService: UserService,
) : CaseLauncherService {
    /**
     * Creates a standalone case owned by [onBehalfOfUserId], sends [task] to [agentName] as
     * its first message, and starts it. Returns immediately with the new case id; the case
     * runs in the background.
     *
     * [sessionContext] is forwarded to [CaseService.addMessage] and embedded on the first
     * [io.whozoss.agentos.sdk.caseEvent.MessageEvent].
     *
     * On permission-grant failure the orphaned case is killed and an [IllegalStateException]
     * is thrown, consistent with the sub-case grant failure handling in
     * [io.whozoss.agentos.caseFlow.CaseServiceImpl.startSubCase].
     */
    override fun launchCase(
        namespaceId: UUID,
        agentName: String,
        task: String,
        onBehalfOfUserId: UUID,
        sessionContext: Map<String, Any?>?,
    ): UUID {
        // create() is called through the CaseService proxy — @Transactional is honoured.
        val case =
            caseService.create(Case(namespaceId = namespaceId, title = task.take(MAX_LAUNCHED_CASE_TITLE_LENGTH)))
        try {
            permissionService.grantPermission(
                onBehalfOfUserId.toString(),
                EntityType.CASE,
                case.id.toString(),
                PermissionRelation.ADMIN,
            )
        } catch (e: Exception) {
            logger.error(e) { "Auto-ADMIN grant failed for launched case ${case.id} (user $onBehalfOfUserId) — killing case" }
            runCatching { caseService.delete(case.id) }
                .onFailure { killErr -> logger.warn(killErr) { "Failed to kill orphaned launched case ${case.id}" } }
            throw IllegalStateException("Failed to grant permissions on launched case ${case.id}: ${e.message}", e)
        }
        val user = userService.getById(onBehalfOfUserId)
        val actor =
            Actor(
                id = onBehalfOfUserId.toString(),
                displayName = user.displayName(),
                role = ActorRole.USER,
            )
        // @mention routes the first message to the requested agent through the normal selectAgent resolution.
        caseService.addMessage(
            caseId = case.id,
            actor = actor,
            content = listOf(MessageContent.Text("@$agentName $task")),
            sessionContext = sessionContext,
        )
        logger.info { "Launched case ${case.id} for user $onBehalfOfUserId, agent=$agentName" }
        return case.id
    }

    companion object : KLogging() {
        /** Maximum character length for a launched case title derived from its task. */
        private const val MAX_LAUNCHED_CASE_TITLE_LENGTH = 80
    }
}
