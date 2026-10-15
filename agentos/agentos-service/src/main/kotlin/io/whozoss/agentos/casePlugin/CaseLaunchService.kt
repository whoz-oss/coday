package io.whozoss.agentos.casePlugin

import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * What a caller (today the `CreateCase` tool) asks for: start [agentName] on [task].
 *
 * The `caller*` fields describe the running case and come from it, never from LLM input.
 *
 * @param callerCaseId The case that launches: it becomes the origin ([Case.originCaseId]) of the new one.
 * @param targetUserId The user the new case runs for. Null, or the caller itself: the caller. Any
 *   other user must exist and be able to read the namespace.
 * @param title Name of the new conversation. Null: derived from the start of [task].
 * @param context What the caller hands to the new case, and nothing else: it becomes the session
 *   context of the first message as is. The calling case's own context is never inherited.
 */
data class CaseLaunchRequest(
    val callerUserId: UUID,
    val callerNamespaceId: UUID,
    val callerCaseId: UUID,
    val agentName: String,
    val task: String,
    val targetUserId: UUID? = null,
    val title: String? = null,
    val context: Map<String, Any?>? = null,
)

sealed interface CaseLaunchOutcome {
    /** The case exists and its first turn has been handed to the runtime; it runs in the background. */
    data class Launched(
        val caseId: UUID,
        val namespaceId: UUID,
        val userId: UUID,
        val agentName: String,
    ) : CaseLaunchOutcome

    /** Nothing was created. [message] is safe to show to the LLM. */
    data class Refused(
        val reason: CaseLaunchRefusal,
        val message: String,
    ) : CaseLaunchOutcome
}

/** Why a launch was refused. The name is the stable `errorType` the `CreateCase` tool reports. */
enum class CaseLaunchRefusal {
    /** The calling user no longer exists. */
    CALLER_NOT_FOUND,

    /** The target user does not exist. */
    USER_NOT_FOUND,

    /** The target user cannot read the namespace. */
    NO_ACCESS,

    /** The agent is unknown, disabled or not deployed to the target user: the three look the same. */
    AGENT_NOT_AVAILABLE,

    /** Creating or starting the case failed. Nothing was left behind. */
    LAUNCH_FAILED,
}

/**
 * Starts a new case from inside an agent run.
 *
 * The new case is a top-level case: it has no [Case.parentCaseId], so nothing waits for it, stopping
 * the calling case leaves it running, it does not count in the delegation depth, it works in its own
 * Exchange directory and Git workspace, and its spend is its own. Where it came from is recorded in
 * [Case.originCaseId], a trace that no behavior follows.
 *
 * What this adds, in the order it is applied:
 *
 * 1. **Target user.** The caller, unless [CaseLaunchRequest.targetUserId] names someone else. Then
 *    the target must exist and read the namespace. No administration right is required of the caller:
 *    who may name another user is decided by the `allowOtherUsers` switch of the integration.
 * 2. **Agent access.** The agent must be deployed to the target user in the namespace: the same
 *    condition as addressing it with an `@mention`.
 *
 * Then it creates the case, gives the target user ADMIN on it (as `CaseController.create` does for
 * the creator) and sends the prompt, addressed to the agent with an `@mention`, as that user. If one
 * of those last two steps fails the case is deleted again, so no empty conversation is left behind.
 *
 * The session context of the first message is exactly what the caller passed in
 * [CaseLaunchRequest.context], usually the output of an enrichment tool: the calling case's own
 * context is never inherited, so the caller hands over only the relevant part. An empty context
 * counts as none.
 *
 * Refusals are thrown as a private exception and turned into [CaseLaunchOutcome.Refused] at the
 * single exit of [launch]; the exception never leaves this class.
 *
 * [caseService] is a `@Lazy` proxy: `CaseService` depends on `AgentService`, which depends on the
 * tool registry that collects the plugin using this class, so resolving it eagerly would close a
 * cycle. Going through the proxy also keeps `create` transactional.
 */
@Service
class CaseLaunchService(
    @Lazy private val caseService: CaseService,
    private val userService: UserService,
    private val agentConfigService: AgentConfigService,
    private val permissionService: PermissionService,
) {
    fun launch(request: CaseLaunchRequest): CaseLaunchOutcome =
        try {
            doLaunch(request)
        } catch (refusal: Refusal) {
            logger.info { "[CaseLaunch] refused (${refusal.reason}): ${refusal.explanation}" }
            CaseLaunchOutcome.Refused(refusal.reason, refusal.explanation)
        }

    private fun doLaunch(request: CaseLaunchRequest): CaseLaunchOutcome.Launched {
        val targetUser = resolveTargetUser(request)
        val agentName = resolveAgentName(request.agentName, request.callerNamespaceId, targetUser.id)

        val case = createCase(request)
        startConversation(case, targetUser, agentName, request)

        logger.info {
            "[CaseLaunch] case ${case.id} launched: agent='$agentName' user=${targetUser.id} " +
                "namespace=${request.callerNamespaceId} origin=${request.callerCaseId} (by user ${request.callerUserId})"
        }
        return CaseLaunchOutcome.Launched(case.id, case.namespaceId, targetUser.id, agentName)
    }

    private fun resolveTargetUser(request: CaseLaunchRequest): User {
        val caller =
            userService.findById(request.callerUserId)
                ?: refuse(CaseLaunchRefusal.CALLER_NOT_FOUND, "The calling user no longer exists.")
        val targetId = request.targetUserId
        return when {
            targetId == null || targetId == caller.id -> caller
            else -> {
                val target = userService.findById(targetId) ?: refuse(CaseLaunchRefusal.USER_NOT_FOUND, "No such user.")
                if (!hasNamespacePermission(target.id, request.callerNamespaceId, Action.READ)) {
                    refuse(CaseLaunchRefusal.NO_ACCESS, "The target user has no access to this namespace.")
                }
                target
            }
        }
    }

    /** Namespace READ is membership. */
    private fun hasNamespacePermission(
        userId: UUID,
        namespaceId: UUID,
        action: Action,
    ): Boolean = permissionService.hasPermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), action)

    /**
     * The agent as it is actually named (casing), if deployed to the user in the namespace.
     * `findDeployed...` is a prefix match, so the result is narrowed to an exact one, as in
     * `AgentServiceImpl.findAgentByName`. Unknown, disabled and not deployed look the same.
     */
    private fun resolveAgentName(
        requested: String,
        namespaceId: UUID,
        userId: UUID,
    ): String =
        agentConfigService
            .findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, requested.lowercase())
            .firstOrNull { it.name.equals(requested, ignoreCase = true) }
            ?.name
            ?: refuse(
                CaseLaunchRefusal.AGENT_NOT_AVAILABLE,
                "Agent '$requested' is not available to the target user in this namespace.",
            )

    /**
     * Creates the case, tracing the calling one as its origin. A failure of `create` is not detailed:
     * the message could leak details the LLM has no use for.
     */
    private fun createCase(request: CaseLaunchRequest): Case =
        try {
            caseService.create(
                Case(
                    namespaceId = request.callerNamespaceId,
                    title = (request.title?.takeIf { it.isNotBlank() } ?: request.task).take(MAX_TITLE_LENGTH),
                    originCaseId = request.callerCaseId,
                ),
            )
        } catch (e: Exception) {
            logger.error(e) { "[CaseLaunch] creating a case from ${request.callerCaseId} failed" }
            refuse(CaseLaunchRefusal.LAUNCH_FAILED, "The case could not be started. Nothing was created.")
        }

    /**
     * Gives the target user ADMIN on [case] and sends the first message as them. The case exists by now, so
     * a failure deletes it again rather than leaving an empty conversation nobody can open.
     */
    private fun startConversation(
        case: Case,
        targetUser: User,
        agentName: String,
        request: CaseLaunchRequest,
    ) {
        try {
            permissionService.grantPermission(
                targetUser.id.toString(),
                EntityType.CASE,
                case.id.toString(),
                PermissionRelation.ADMIN,
            )
            // The @mention routes the message to the agent through the normal agent selection.
            caseService.addMessage(
                caseId = case.id,
                actor = Actor(id = targetUser.id.toString(), displayName = targetUser.displayName(), role = ActorRole.USER),
                content = listOf(MessageContent.Text("@$agentName ${request.task}")),
                sessionContext = request.context?.takeIf { it.isNotEmpty() },
            )
        } catch (e: Exception) {
            logger.error(e) { "[CaseLaunch] starting case ${case.id} for user ${targetUser.id} failed, deleting it" }
            runCatching { caseService.delete(case.id) }
                .onFailure { logger.warn(it) { "[CaseLaunch] could not delete the half-started case ${case.id}" } }
            refuse(CaseLaunchRefusal.LAUNCH_FAILED, "The case could not be started. Nothing was created.")
        }
    }

    /** [explanation] is kept as a non-null property: `Throwable.message` is nullable. No stack trace, it is control flow. */
    private class Refusal(
        val reason: CaseLaunchRefusal,
        val explanation: String,
    ) : RuntimeException(explanation, null, false, false)

    private fun refuse(
        reason: CaseLaunchRefusal,
        message: String,
    ): Nothing = throw Refusal(reason, message)

    companion object : KLogging() {
        /** Maximum length of the title of a launched case, given or derived from its prompt. */
        private const val MAX_TITLE_LENGTH = 80
    }
}
