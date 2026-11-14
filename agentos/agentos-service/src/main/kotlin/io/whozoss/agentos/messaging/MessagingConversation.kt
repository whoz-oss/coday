package io.whozoss.agentos.messaging

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * A chat on a messaging platform, and the case its messages go to.
 *
 * The chat and the case are kept apart so that the chat can move from one case to another (`/new`, `/cases`,
 * "Continue on Telegram" from a case) while the case stays the single record of the conversation: nothing is
 * copied back into the chat afterwards. One active conversation per chat ([conversationKey], unique among
 * active conversations); several conversations may point to the same case.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MessagingConversation(
    override val metadata: EntityMetadata = EntityMetadata(),
    val platform: MessagingPlatform,
    /** The bot the chat is held with. */
    val accountId: String,
    val chatId: String,
    /** The AgentOS user the chat belongs to: the linked owner of a direct chat. */
    val userId: UUID,
    /** The case the next message goes to. Null before the first message and after `/new`. */
    val caseId: UUID? = null,
    /** Platform id of the last message taken in, so that a message delivered twice is taken in once. */
    val lastInboundMessageId: Long? = null,
) : Entity {
    val conversationKey: String get() = conversationKey(platform, accountId, chatId)

    companion object {
        fun conversationKey(
            platform: MessagingPlatform,
            accountId: String,
            chatId: String,
        ): String = "$platform:$accountId:$chatId"
    }
}
