package io.whozoss.agentos.casePlugin

import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.context.UserSessionContextResolver
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.prompt.PromptService
import io.whozoss.agentos.prompt.PromptTranslationService
import io.whozoss.agentos.prompt.TextKind
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.scheduledPrompt.UserContextResult
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.context.annotation.Lazy
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Starts a new case from inside an agent run.
 *
 * The new case is a top-level case: it has no [Case.parentCaseId], so nothing waits for it, stopping
 * the calling case leaves it running, it does not count in the delegation depth, it works in its own
 * Exchange directory and Git workspace, and its spend is its own. Where it came from is recorded in
 * [Case.originCaseId], which only the start depth rule below reads.
 *
 * What this adds, in the order it is applied:
 *
 * 1. **Start depth.** A case started by this service cannot start another one, nor can a case delegated from
 *    it: the [Case.parentCaseId] chain of the calling case is walked up, which delegation caps at a few
 *    levels. A started case counts in no delegation depth, so without this an agent could start itself
 *    without end. One level is all a caller like AgentLoop needs.
 * 2. **Target user.** The caller, unless [CaseStarterRequest.targetUserId] names someone else. Then the
 *    caller must be an ADMIN of the namespace (the federation: Namespace WRITE, which super-admins also
 *    hold), and the target must exist and read the namespace. A plain member cannot run a case on behalf
 *    of someone else.
 * 3. **Agent access.** The agent must be deployed to the target user in the namespace: the same
 *    condition as addressing it with an `@mention`.
 * 4. **User context.** The context of the target user is resolved by [UserSessionContextResolver], as for a
 *    scheduled prompt: what the `UserContextProvider` plugin returns, with the user's `preferredLanguage`
 *    on top. It comes last because the provider may be an external request: a refused start never makes one.
 *    A provider failure, permanent or transient, is thrown ([UserContextUnavailableException]) before
 *    anything is written.
 *
 * Then it creates the case, gives the target user ADMIN on it (as `CaseController.create` does for
 * the creator) and sends the prompt, addressed to the agent with an `@mention`, as that user. If one
 * of those last two steps fails the case is soft-deleted, so no empty conversation shows up, and the
 * failure is rethrown. An ADMIN grant already made is not revoked.
 *
 * **Who checks what.** This service owns the rules that protect users and the platform: who the case runs
 * for (the caller being ADMIN of the namespace, the target's access to the namespace), which agent may work
 * for them (deployed to the target), and how far starts go (one level). The `FreeCaseStarter` tool validates its input and scopes what its
 * integration instance may start (the agent allowlist); those checks are not repeated here.
 *
 * The session context of the first message is the caller's [CaseStarterRequest.sessionContext], passed on as
 * given, with the context the system resolves for the target user on top: what the `UserContextProvider`
 * returns, with the user's `preferredLanguage`. On a conflicting key the resolved one wins; a key the
 * resolution does not produce is passed on as the caller gave it. This service knows nothing of the map's
 * format: what the caller puts in it is the caller's business. The context is resolved for the **target**
 * user, not the caller: when a case runs for someone else, it carries that person's identity.
 *
 * The calling case's own context is never inherited. Without either source, there is no session context.
 *
 * The session context is resolved in every flow that starts a case through this service, so a caller such
 * as the loop hands over the entity only and does not resolve the user's context itself.
 *
 * **Refusals and failures.** [start] returns the [StartedCase], or throws. A [CaseStarterRefusedException] is a
 * business decision, taken before anything is written: the caller, the tool, catches it and reports it. Anything
 * else (an infrastructure failure, a bug, a calling user that vanished) is thrown as is, like any tool failure,
 * to the error boundary above the tool: the agent layer, which logs it and reports it to the LLM.
 *
 * [caseService] is a `@Lazy` proxy: `CaseService` depends on `AgentService`, which depends on the
 * tool registry that collects the plugin using this class, so resolving it eagerly would close a
 * cycle. Going through the proxy also keeps `create` transactional.
 */
@Service
class CaseStarterService(
    @Lazy private val caseService: CaseService,
    private val userService: UserService,
    private val agentConfigService: AgentConfigService,
    private val permissionService: PermissionService,
    private val userSessionContextResolver: UserSessionContextResolver,
    private val promptTranslationService: PromptTranslationService,
    private val promptService: PromptService,
) {
    /**
     * Starts the case and returns it.
     *
     * @throws CaseStarterRefusedException when a business rule refuses the start, before anything is written.
     *   Any other failure is thrown as is.
     */
    fun start(request: CaseStarterRequest): StartedCase {
        if (isInStartedCase(request.callerCaseId)) {
            refuse(
                CaseStarterRefusal.START_DEPTH_EXCEEDED,
                "The current case was itself started by another case, so it cannot start a new one.",
            )
        }
        // Pre-check: if a dispatchKey is set and a non-deleted case already carries it,
        // refuse immediately before any expensive operations (no user resolution, no LLM).
        if (request.dispatchKey != null && caseService.findByDispatchKey(request.dispatchKey) != null) {
            refuse(
                CaseStarterRefusal.ALREADY_DISPATCHED,
                "A case with dispatchKey '${request.dispatchKey}' already exists — this dispatch was already delivered.",
            )
        }
        val targetUser = resolveTargetUser(request)
        val agentName =
            findDeployedAgent(
                agentName = request.agentName,
                namespaceId = request.callerNamespaceId,
                userId = targetUser.id,
            )?.name ?: refuse(
                CaseStarterRefusal.AGENT_NOT_AVAILABLE,
                "Agent '${request.agentName}' is not available to the target user in this namespace.",
            )

        val sessionContext = resolveSessionContext(targetUser = targetUser, request = request)

        val (effectivePrompt, effectiveTitle) = resolveMessage(request, targetUser)

        val case = createCase(request, effectiveTitle ?: effectivePrompt)
        startConversation(
            case = case,
            targetUser = targetUser,
            agentName = agentName,
            sessionContext = sessionContext,
            prompt = effectivePrompt,
        )

        logger.info {
            "[CaseStarter] case ${case.id} started: agent='$agentName' user=${targetUser.id} " +
                "namespace=${request.callerNamespaceId} origin=${request.callerCaseId} (by user ${request.callerUserId})"
        }
        return StartedCase(caseId = case.id, userId = targetUser.id, agentName = agentName)
    }

    /**
     * Whether the calling case was started by another case, itself or through the cases it was delegated from.
     * The calling case is running: missing, it is a bug. Its ancestors are read even when deleted, since a
     * deleted case's sub-cases may still run and its origin does not go away. Delegation is capped, so this
     * reads a few cases at most.
     */
    private fun isInStartedCase(callerCaseId: UUID): Boolean {
        val callerCase =
            checkNotNull(caseService.findById(callerCaseId)) { "Calling case $callerCaseId no longer exists" }
        return generateSequence(callerCase, ::findParent).any { ancestor -> ancestor.originCaseId != null }
    }

    /** The case [case] was delegated from, deleted or not, or null for a top-level case. */
    private fun findParent(case: Case): Case? = case.parentCaseId?.let { parentId -> caseService.findById(parentId, withRemoved = true) }

    private fun resolveTargetUser(request: CaseStarterRequest): User {
        // Null when the case runs for the caller: no userId, or the caller's own.
        val otherUserId = request.targetUserId?.takeIf { it != request.callerUserId }
        return when {
            otherUserId == null -> {
                resolveCaller(request.callerUserId)
            }

            !hasNamespacePermission(request.callerUserId, request.callerNamespaceId, Action.WRITE) -> {
                refuse(
                    CaseStarterRefusal.FORBIDDEN,
                    "Only an administrator of the namespace can start a case for another user.",
                )
            }

            else -> {
                resolveOtherUser(userId = otherUserId, namespaceId = request.callerNamespaceId)
            }
        }
    }

    /** The caller comes from the run, whose user was checked when it started: missing, it is a bug. */
    private fun resolveCaller(callerUserId: UUID): User =
        checkNotNull(userService.findById(callerUserId)) { "Calling user $callerUserId no longer exists" }

    private fun resolveOtherUser(
        userId: UUID,
        namespaceId: UUID,
    ): User {
        val user = userService.findById(userId) ?: refuse(CaseStarterRefusal.TARGET_USER_NOT_FOUND, "No such user.")
        if (!hasNamespacePermission(user.id, namespaceId, Action.READ)) {
            refuse(CaseStarterRefusal.TARGET_NO_ACCESS, "The target user has no access to this namespace.")
        }
        return user
    }

    /** Namespace WRITE is namespace administration (super-admins pass through the evaluator); READ is access. */
    private fun hasNamespacePermission(
        userId: UUID,
        namespaceId: UUID,
        action: Action,
    ): Boolean =
        permissionService.hasPermission(
            userId = userId.toString(),
            entityType = EntityType.NAMESPACE,
            entityId = namespaceId.toString(),
            action = action,
        )

    /**
     * The agent named [agentName] among those deployed to the user in the namespace, or null if there is none.
     * The query already does the access work: it returns only enabled, non-removed agents deployed to the user,
     * and matches the name case-insensitively on both sides, so [agentName] is passed as given. What it matches is
     * a *prefix* (autocomplete semantics, `Reporter` also returns `Reporter-v2`), so the result is narrowed to the
     * agent whose name is [agentName] in full. Unknown, disabled and not deployed look the same.
     *
     * The start uses the canonical name of the agent found, not the one the caller typed, for the `@mention` of
     * the first message, the log and the [StartedCase].
     */
    private fun findDeployedAgent(
        agentName: String,
        namespaceId: UUID,
        userId: UUID,
    ): AgentConfig? =
        agentConfigService
            .findDeployedByNamespaceIdAndUserIdAndName(
                namespaceId = namespaceId,
                userId = userId,
                agentName = agentName,
            ).firstOrNull { it.name.equals(agentName, ignoreCase = true) }

    /**
     * The session context of the first message: the caller's [CaseStarterRequest.sessionContext], under the
     * context resolved for [targetUser], which wins on a conflicting key. Null when both are empty.
     *
     * A failure of the provider is thrown, before anything is written. The reason the provider gives is
     * logged but kept out of the exception, which reaches the LLM: it may carry the body of an upstream error.
     */
    private fun resolveSessionContext(
        targetUser: User,
        request: CaseStarterRequest,
    ): Map<String, Any?>? {
        val resolved =
            when (val result = userSessionContextResolver.resolve(targetUser.externalId, request.callerNamespaceId)) {
                is UserContextResult.Success -> {
                    if (result.sessionContext == null && userSessionContextResolver.hasProvider()) {
                        logger.warn {
                            "[CaseStarter] UserContextProvider returned Success(null) for user ${targetUser.id}" +
                                " — no provider context will be injected. Check provider configuration."
                        }
                    }
                    userSessionContextResolver.mergePreferredLanguage(
                        providerContext = result.sessionContext,
                        preferredLanguage = targetUser.preferredLanguage?.takeIf { it.isNotBlank() },
                    )
                }

                is UserContextResult.PermanentFailure -> {
                    logger.error { "[CaseStarter] permanent context failure for user ${targetUser.id}: ${result.reason}" }
                    throw UserContextUnavailableException(
                        "The context of the target user could not be resolved (permanent failure): " +
                            "the case was not started.",
                    )
                }

                is UserContextResult.TransientFailure -> {
                    logger.warn { "[CaseStarter] transient context failure for user ${targetUser.id}: ${result.reason}" }
                    throw UserContextUnavailableException(
                        "The context of the target user could not be resolved (temporary failure, may succeed later): " +
                            "the case was not started.",
                    )
                }
            }
        return ((request.sessionContext ?: emptyMap()) + (resolved ?: emptyMap())).takeIf { it.isNotEmpty() }
    }

    /**
     * Resolves the prompt text and optional title to use when creating the case.
     *
     * Resolution order:
     * 1. When [CaseStarterRequest.promptId] is set, delegate to [resolveStoredPrompt], which reads the
     *    persisted [io.whozoss.agentos.prompt.Prompt] and reuses its curated translations via
     *    `PromptService.translate(...)`. A missing or empty prompt refuses the start.
     * 2. Otherwise fall through to [maybeTranslate] (the existing free-text LLM path), which translates
     *    [CaseStarterRequest.prompt] on-the-fly when the target user speaks a different language.
     */
    private fun resolveMessage(
        request: CaseStarterRequest,
        targetUser: User,
    ): Pair<String, String?> {
        val targetLanguage = targetUser.preferredLanguage?.takeIf { it.isNotBlank() }
        return when (val promptId = request.promptId) {
            null -> maybeTranslate(request, targetLanguage, request.callerNamespaceId)
            else -> resolveStoredPrompt(promptId, targetLanguage, request)
        }
    }

    /**
     * Resolves content and title from a persisted [io.whozoss.agentos.prompt.Prompt].
     *
     * When [targetLanguage] is null the source-language content is used directly.
     * Otherwise `PromptService.translate(...)` is called, which reuses persisted translations
     * before falling back to an LLM.
     *
     * A [NoSuchElementException] from `translate` (prompt not found) and a missing/blank content
     * both surface as [CaseStarterRefusal.PROMPT_NOT_FOUND]. Any other translation exception
     * degrades gracefully to the source-language content rather than failing the start.
     */
    private fun resolveStoredPrompt(
        promptId: UUID,
        targetLanguage: String?,
        request: CaseStarterRequest,
    ): Pair<String, String?> {
        if (targetLanguage == null) {
            val prompt =
                promptService.findById(promptId)
                    ?: refuse(CaseStarterRefusal.PROMPT_NOT_FOUND, "Prompt $promptId not found.")
            val content =
                prompt.content.firstOrNull { it.isNotBlank() }
                    ?: refuse(CaseStarterRefusal.PROMPT_NOT_FOUND, "Prompt $promptId has no usable content.")
            return content to (prompt.title ?: request.title)
        }
        // Try translated path; NoSuchElementException = prompt not found → refuse.
        val translation =
            try {
                promptService.translate(
                    id = promptId,
                    targetLanguage = targetLanguage,
                    callerNamespaceId = request.callerNamespaceId,
                )
            } catch (e: NoSuchElementException) {
                refuse(CaseStarterRefusal.PROMPT_NOT_FOUND, "Prompt $promptId not found: ${e.message}")
            } catch (e: Exception) {
                // Degrade to source-language content rather than failing the start.
                logger.warn(e) { "[CaseStarter] translation of stored prompt $promptId failed — using source content" }
                val prompt =
                    promptService.findById(promptId)
                        ?: refuse(
                            CaseStarterRefusal.PROMPT_NOT_FOUND,
                            "Prompt $promptId not found after translation failure.",
                        )
                val content =
                    prompt.content.firstOrNull { it.isNotBlank() }
                        ?: refuse(CaseStarterRefusal.PROMPT_NOT_FOUND, "Prompt $promptId has no usable content.")
                return content to (prompt.title ?: request.title)
            }
        val content =
            translation.content.firstOrNull { it.isNotBlank() }
                ?: refuse(
                    CaseStarterRefusal.PROMPT_NOT_FOUND,
                    "Prompt $promptId has no usable content after translation.",
                )
        return content to (translation.title ?: request.title)
    }

    /**
     * Translates [CaseStarterRequest.prompt] and [CaseStarterRequest.title] when translation is opted in.
     * Returns originals unchanged on any failure or when conditions are not met.
     */
    private fun maybeTranslate(
        request: CaseStarterRequest,
        targetLanguage: String?,
        namespaceId: UUID,
    ): Pair<String, String?> {
        val sourceLanguage = request.translateFrom
        if (sourceLanguage == null ||
            targetLanguage == null ||
            targetLanguage.equals(sourceLanguage, ignoreCase = true)
        ) {
            return request.prompt to request.title
        }
        val translatedPrompt =
            runCatching {
                promptTranslationService.translateText(
                    text = request.prompt,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    namespaceId = namespaceId,
                    kind = TextKind.CONTENT,
                )
            }.onFailure { e ->
                logger.warn(e) { "[CaseStarter] translation failed for prompt — using original" }
            }.getOrElse { request.prompt }

        val translatedTitle =
            request.title?.let { titleText ->
                runCatching {
                    promptTranslationService.translateText(
                        text = titleText,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        namespaceId = namespaceId,
                        kind = TextKind.TITLE,
                    )
                }.onFailure { e ->
                    logger.warn(e) { "[CaseStarter] translation failed for title — using original" }
                }.getOrElse { titleText }
            }
        return translatedPrompt to translatedTitle
    }

    /** Creates the case, tracing the calling one as its origin. [effectiveTitle] is already translated. */
    private fun createCase(
        request: CaseStarterRequest,
        effectiveTitle: String,
    ): Case =
        try {
            caseService.create(
                Case(
                    namespaceId = request.callerNamespaceId,
                    title = effectiveTitle.take(MAX_TITLE_LENGTH),
                    originCaseId = request.callerCaseId,
                    scheduledPromptId = request.scheduledPromptId,
                    dispatchKey = request.dispatchKey,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            // Race backstop: the pre-check is not atomic. A concurrent create with the same
            // dispatchKey can race past the pre-check and collide at the constraint level.
            // Translate the constraint violation into the same ALREADY_DISPATCHED refusal.
            logger.info { "[CaseStarter] dispatchKey constraint violation (race) for key '${request.dispatchKey}': ${e.message}" }
            refuse(
                CaseStarterRefusal.ALREADY_DISPATCHED,
                "A case with dispatchKey '${request.dispatchKey}' already exists — this dispatch was already delivered (concurrent create).",
            )
        }

    /**
     * Gives the target user ADMIN on [case] and sends the first message as them. The case exists by now, so
     * a failure soft-deletes it rather than leaving an empty conversation, then is rethrown for the caller
     * to report. An ADMIN grant already made is not revoked.
     *
     * [prompt] is the already-translated (or original) prompt text.
     */
    private fun startConversation(
        case: Case,
        targetUser: User,
        agentName: String,
        sessionContext: Map<String, Any?>?,
        prompt: String,
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
                actor =
                    Actor(
                        id = targetUser.id.toString(),
                        displayName = targetUser.displayName(),
                        role = ActorRole.USER,
                    ),
                content = listOf(MessageContent.Text("@$agentName $prompt")),
                sessionContext = sessionContext,
            )
        } catch (e: Exception) {
            // The failure itself is logged by whoever handles it; this only records the compensation.
            logger.warn { "[CaseStarter] starting case ${case.id} for user ${targetUser.id} failed, deleting it" }
            runCatching { caseService.delete(case.id) }
                .onFailure { logger.warn(it) { "[CaseStarter] could not delete the half-started case ${case.id}" } }
            throw e
        }
    }

    /** Logs and throws the refusal: the business rule that failed, before anything is written. */
    private fun refuse(
        reason: CaseStarterRefusal,
        message: String,
    ): Nothing {
        logger.info { "[CaseStarter] refused ($reason): $message" }
        throw CaseStarterRefusedException(reason = reason, message = message)
    }

    companion object : KLogging() {
        /** Maximum length of the title of a started case, given or derived from its prompt. */
        private const val MAX_TITLE_LENGTH = 80
    }
}
