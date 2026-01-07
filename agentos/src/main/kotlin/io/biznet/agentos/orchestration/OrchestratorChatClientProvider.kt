package io.biznet.agentos.orchestration

import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * Provider for ChatClient instances.
 * Returns a configured ChatClient ready to use.
 */
@Component
class OrchestratorChatClientProvider(
    @Qualifier("anthropicChatClient") private val chatClient: ChatClient,
) {
    fun getChatClient(): ChatClient = chatClient
}
