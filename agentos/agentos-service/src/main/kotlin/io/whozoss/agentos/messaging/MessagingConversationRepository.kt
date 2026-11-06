package io.whozoss.agentos.messaging

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Persistence for [MessagingConversation]. The parent of a conversation is the AgentOS user it belongs to.
 */
interface MessagingConversationRepository : EntityRepository<MessagingConversation, UUID> {
    /** The active conversation of a chat, or null before the chat's first message. */
    fun findByConversationKey(conversationKey: String): MessagingConversation?

    /** The active conversations whose next message goes to [caseId]. */
    fun findByCaseId(caseId: UUID): List<MessagingConversation>
}
