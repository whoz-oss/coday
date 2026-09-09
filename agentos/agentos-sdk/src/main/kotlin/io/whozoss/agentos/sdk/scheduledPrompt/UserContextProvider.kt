package io.whozoss.agentos.sdk.scheduledPrompt

import org.pf4j.ExtensionPoint
import java.util.UUID

/**
 * SPI for plugins that provide user context injected into a scheduled case.
 *
 * Implementations are discovered via PF4J at startup and exposed as an optional Spring bean
 * injected into [io.whozoss.agentos.scheduledPrompt.ScheduledPromptExecutor].
 * If absent or if context provisioning fails, execution continues without sessionContext (non-fatal degradation).
 */
interface UserContextProvider : ExtensionPoint {
    /**
     * Builds the sessionContext map to inject into [io.whozoss.agentos.caseFlow.CaseService.addMessage].
     *
     * @param userExternalId Whoz user ObjectId (from [io.whozoss.agentos.user.User.externalId] in AgentOS)
     * @param namespaceId AgentOS namespace UUID
     * @return sessionContext map to inject into AddMessageRequest, or null on failure
     */
    fun provideUserContext(
        userExternalId: String,
        namespaceId: UUID,
    ): Map<String, Any?>?
}
