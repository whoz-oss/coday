package io.whozoss.agentos.sdk.scheduledPrompt

import org.pf4j.ExtensionPoint
import java.util.UUID

/**
 * SPI for plugins that provide user context injected into a scheduled case.
 *
 * Implementations are discovered via PF4J at startup and exposed as an optional Spring bean
 * injected into [io.whozoss.agentos.scheduledPrompt.ScheduledPromptExecutor].
 *
 * ### Return contract
 *
 * Return a [UserContextResult] to signal the outcome:
 *
 * - [UserContextResult.Success] — context resolved (sessionContext may still be null for
 *   intentional no-context cases); execution continues normally.
 * - [UserContextResult.PermanentFailure] — misconfiguration or 4xx; the UserRun is marked
 *   `FAILED` immediately without retrying.
 * - [UserContextResult.TransientFailure] — timeout or 5xx; processing stops and the lease
 *   expiry mechanism reclaims the UserRun on the next scheduler tick.
 *
 * ### Absence of a provider
 *
 * When no plugin registers a [UserContextProvider], the executor continues without
 * sessionContext — non-fatal degradation, same as before.
 *
 * ### Exception handling
 *
 * Unexpected exceptions thrown by [provideUserContext] are caught by the executor and
 * treated as [UserContextResult.TransientFailure] with a warning log.
 */
interface UserContextProvider : ExtensionPoint {
    /**
     * Builds the sessionContext map to inject into [io.whozoss.agentos.caseFlow.CaseService.addMessage].
     *
     * @param userExternalId Whoz user ObjectId (from [io.whozoss.agentos.user.User.externalId] in AgentOS)
     * @param namespaceId AgentOS namespace UUID
     * @return [UserContextResult] describing the outcome; never throw for expected failures—
     *   use [UserContextResult.PermanentFailure] or [UserContextResult.TransientFailure] instead.
     */
    fun provideUserContext(
        userExternalId: String,
        namespaceId: UUID,
    ): UserContextResult
}
