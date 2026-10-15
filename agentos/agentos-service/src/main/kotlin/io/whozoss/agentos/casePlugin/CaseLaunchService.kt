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
 * The `caller*` fields describe the running case and come from it, never from LLM input; so does
 * [allowOtherUsers].
 *
 * @param callerCaseId The case that launches: it becomes the origin ([Case.originCaseId]) of the new one.
 * @param targetUserId The user the new case runs for. Null, or the caller itself: the caller. Any
 *   other user requires [allowOtherUsers], must exist and must be able to read the namespace.
 * @param title Name of the new conversation. Null or blank: derived from the start of [task].
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
    /**
     * Whether [targetUserId] may name a user other than the caller. Set from the caller's own
     * configuration (the `allowOtherUsers` switch of a `CASE_LAUNCHER` integration), never from LLM input.
     */
    val allowOtherUsers: Boolean = false,
)

sealed interface CaseLaunchOutcome {
    /** The case exists and its first turn has been handed to the runtime; it runs in the background. */
    data class Launched(
        val caseId: UUID,
        val userId: UUID,
        val agentName: String,
    ) : CaseLaunchOutcome

    /**
     * A business refusal, decided before anything is written: nothing was created. [message] is safe to
     * show to the LLM.
     */
    data class Refused(
        val reason: CaseLaunchRefusal,
        val message: String,
    ) : CaseLaunchOutcome
}

/**
 * Why a launch was refused: a business rule, never a failure. The name is the stable `errorType` the
 * `CreateCase` tool reports.
 */
enum class CaseLaunchRefusal {
    /** The request names another user, which [CaseLaunchRequest.allowOtherUsers] does not allow. */
    OTHER_USERS_NOT_ALLOWED,

    /** The target user does not exist. */
    TARGET_USER_NOT_FOUND,

    /** The target user cannot read the namespace. */
    TARGET_NO_ACCESS,

    /** The agent is unknown, disabled or not deployed to the target user: the three look the same. */
    AGENT_NOT_AVAILABLE,
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
 *    [CaseLaunchRequest.allowOtherUsers] must be set, and the target must exist and read the namespace.
 *    No administration right is required of the caller: the switch, set by whoever configured the
 *    caller, is what decides.
 * 2. **Agent access.** The agent must be deployed to the target user in the namespace: the same
 *    condition as addressing it with an `@mention`.
 *
 * Then it creates the case, gives the target user ADMIN on it (as `CaseController.create` does for
 * the creator) and sends the prompt, addressed to the agent with an `@mention`, as that user. If one
 * of those last two steps fails the case is soft-deleted, so no empty conversation shows up, and the
 * failure is rethrown. An ADMIN grant already made is not revoked.
 *
 * **Who checks what.** This service owns the rules that protect users: who the case runs for
 * ([CaseLaunchRequest.allowOtherUsers], the target's access to the namespace) and which agent may work
 * for them (deployed to the target). The `CreateCase` tool validates its input and scopes what its
 * integration instance may launch (the agent allowlist); those checks are not repeated here.
 *
 * The session context of the first message is exactly what the caller passed in
 * [CaseLaunchRequest.context], usually the output of an enrichment tool: the calling case's own
 * context is never inherited, so the caller hands over only the relevant part. An empty context
 * counts as none.
 *
 * **Refusals and failures.** A [CaseLaunchOutcome.Refused] is a business decision, taken before anything
 * is written. Anything else (an infrastructure failure, a bug, a calling user that vanished) is thrown as
 * is, like any tool failure, to the caller's error boundary: for a tool call, the agent layer, which logs
 * it and reports it to the LLM. Refusals travel as a private exception, turned into
 * [CaseLaunchOutcome.Refused] at the single exit of [launch]; that exception never leaves this class.
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
    /** [CaseLaunchOutcome.Refused] for a business refusal; any other failure is thrown. */
    fun launch(request: CaseLaunchRequest): CaseLaunchOutcome =
        try {
            doLaunch(request)
        } catch (refusal: Refusal) {
            logger.info { "[CaseLaunch] refused (${refusal.reason}): ${refusal.explanation}" }
            CaseLaunchOutcome.Refused(reason = refusal.reason, message = refusal.explanation)
        }

    private fun doLaunch(request: CaseLaunchRequest): CaseLaunchOutcome.Launched {
        val targetUser = resolveTargetUser(request)
        val agentName =
            resolveAgentName(
                requested = request.agentName,
                namespaceId = request.callerNamespaceId,
                userId = targetUser.id,
            )

        val case = createCase(request)
        startConversation(case = case, targetUser = targetUser, agentName = agentName, request = request)

        logger.info {
            "[CaseLaunch] case ${case.id} launched: agent='$agentName' user=${targetUser.id} " +
                "namespace=${request.callerNamespaceId} origin=${request.callerCaseId} (by user ${request.callerUserId})"
        }
        return CaseLaunchOutcome.Launched(caseId = case.id, userId = targetUser.id, agentName = agentName)
    }

    private fun resolveTargetUser(request: CaseLaunchRequest): User {
        // Null when the case runs for the caller: no userId, or the caller's own.
        val otherUserId = request.targetUserId?.takeIf { it != request.callerUserId }
        return when {
            otherUserId == null -> resolveCaller(request.callerUserId)
            !request.allowOtherUsers ->
                refuse(CaseLaunchRefusal.OTHER_USERS_NOT_ALLOWED, "Cases can only be started for the current user.")
            else -> resolveOtherUser(userId = otherUserId, namespaceId = request.callerNamespaceId)
        }
    }

    /** The caller comes from the run, whose user was checked when it started: missing, it is a bug. */
    private fun resolveCaller(callerUserId: UUID): User =
        checkNotNull(userService.findById(callerUserId)) { "Calling user $callerUserId no longer exists" }

    private fun resolveOtherUser(
        userId: UUID,
        namespaceId: UUID,
    ): User {
        val user = userService.findById(userId) ?: refuse(CaseLaunchRefusal.TARGET_USER_NOT_FOUND, "No such user.")
        if (!canReadNamespace(userId = user.id, namespaceId = namespaceId)) {
            refuse(CaseLaunchRefusal.TARGET_NO_ACCESS, "The target user has no access to this namespace.")
        }
        return user
    }

    /** Namespace READ is membership. */
    private fun canReadNamespace(
        userId: UUID,
        namespaceId: UUID,
    ): Boolean =
        permissionService.hasPermission(
            userId = userId.toString(),
            entityType = EntityType.NAMESPACE,
            entityId = namespaceId.toString(),
            action = Action.READ,
        )

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
            .findDeployedByNamespaceIdAndUserIdAndName(
                namespaceId = namespaceId,
                userId = userId,
                agentName = requested.lowercase(),
            )
            .firstOrNull { it.name.equals(requested, ignoreCase = true) }
            ?.name
            ?: refuse(
                CaseLaunchRefusal.AGENT_NOT_AVAILABLE,
                "Agent '$requested' is not available to the target user in this namespace.",
            )

    /** Creates the case, tracing the calling one as its origin. */
    private fun createCase(request: CaseLaunchRequest): Case =
        caseService.create(
            Case(
                namespaceId = request.callerNamespaceId,
                title = (request.title?.takeIf { it.isNotBlank() } ?: request.task).take(MAX_TITLE_LENGTH),
                originCaseId = request.callerCaseId,
            ),
        )

    /**
     * Gives the target user ADMIN on [case] and sends the first message as them. The case exists by now, so
     * a failure soft-deletes it rather than leaving an empty conversation, then is rethrown for the caller
     * to report. An ADMIN grant already made is not revoked.
     */
    private fun startConversation(
        case: Case,
        targetUser: User,
        agentName: String,
        request: CaseLaunchRequest,
    ) {
        try {
            permissionService.grantPermission(
                userId = targetUser.id.toString(),
                entityType = EntityType.CASE,
                entityId = case.id.toString(),
                relation = PermissionRelation.ADMIN,
            )
            // The @mention routes the message to the agent through the normal agent selection.
            caseService.addMessage(
                caseId = case.id,
                actor = Actor(id = targetUser.id.toString(), displayName = targetUser.displayName(), role = ActorRole.USER),
                content = listOf(MessageContent.Text("@$agentName ${request.task}")),
                sessionContext = request.context?.takeIf { it.isNotEmpty() },
            )
        } catch (e: Exception) {
            // The failure itself is logged by whoever handles it; this only records the compensation.
            logger.warn { "[CaseLaunch] starting case ${case.id} for user ${targetUser.id} failed, deleting it" }
            runCatching { caseService.delete(case.id) }
                .onFailure { logger.warn(it) { "[CaseLaunch] could not delete the half-started case ${case.id}" } }
            throw e
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
