package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.caseFlow.SessionContextKeys
import io.whozoss.agentos.sdk.scheduledPrompt.UserContextProvider
import io.whozoss.agentos.sdk.scheduledPrompt.UserContextResult
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Resolves the effective [sessionContext] to inject into [io.whozoss.agentos.caseFlow.CaseService.addMessage]
 * for a given end-user.
 *
 * Shared by [ScheduledPromptExecutor] and [io.whozoss.agentos.agent.LoopWorkflowRunner] so that
 * both code paths produce identical context enrichment:
 * 1. Call the optional [UserContextProvider] plugin — returns business context (e.g. talent profile).
 * 2. Overlay [preferredLanguage] on top: the user's stored language preference is the authoritative
 *    source and always wins over anything the provider may put under the same key.
 *
 * When no [UserContextProvider] is registered, [resolve] returns [UserContextResult.Success](null)
 * so callers can proceed without context — same behaviour as before the provider existed.
 *
 * Unexpected exceptions thrown by the provider are caught and returned as
 * [UserContextResult.TransientFailure] so the caller can decide whether to retry or skip.
 */
@Service
class UserSessionContextResolver(
    private val userContextProvider: UserContextProvider? = null,
) {
    /** Returns true when a [UserContextProvider] plugin is registered. */
    fun hasProvider(): Boolean = userContextProvider != null

    /**
     * Resolves the raw context from the [UserContextProvider].
     *
     * Pure — no side effects. The caller is responsible for acting on the outcome:
     * - [UserContextResult.Success]: call [mergePreferredLanguage] to produce the effective map.
     * - [UserContextResult.PermanentFailure]: skip this user permanently (mark FAILED).
     * - [UserContextResult.TransientFailure]: leave for retry (lease expiry / next tick).
     */
    fun resolve(
        userExternalId: String,
        namespaceId: UUID,
    ): UserContextResult =
        userContextProvider?.let { provider ->
            runCatching {
                provider.provideUserContext(
                    userExternalId = userExternalId,
                    namespaceId = namespaceId,
                )
            }.getOrElse { e ->
                logger.warn(e) {
                    "[UserSessionContextResolver] UserContextProvider threw unexpectedly" +
                        " for user='$userExternalId' namespace=$namespaceId" +
                        " — treating as transient failure"
                }
                UserContextResult.TransientFailure(e.message ?: "Unexpected exception")
            }
        } ?: UserContextResult.Success(null)

    /**
     * Overlays [preferredLanguage] onto [providerContext], producing the effective session context.
     *
     * The user's stored [preferredLanguage] is the authoritative source for language:
     * it always wins over anything the provider may put under [SessionContextKeys.PREFERRED_LANGUAGE].
     * Providers supply business context (e.g. talent profile), not language preference.
     *
     * Returns `null` when both [providerContext] and [preferredLanguage] are null — no context to inject.
     */
    fun mergePreferredLanguage(
        providerContext: Map<String, Any?>?,
        preferredLanguage: String?,
    ): Map<String, Any?>? =
        when {
            preferredLanguage == null -> providerContext
            else -> (providerContext ?: emptyMap()) + mapOf(SessionContextKeys.PREFERRED_LANGUAGE to preferredLanguage)
        }

    companion object : KLogging()
}
