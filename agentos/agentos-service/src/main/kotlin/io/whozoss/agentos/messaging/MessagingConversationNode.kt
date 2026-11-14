package io.whozoss.agentos.messaging

import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [MessagingConversation].
 *
 * An active row also carries the `ActiveMessagingConversation` label, set and removed by
 * [Neo4jMessagingConversationRepository]; the uniqueness of [conversationKey] is declared on that label.
 *
 * No edge to the case: [caseId] changes whenever the chat moves to another case, and a scalar keeps that a
 * single property write. Conversations of a case are found through the index on [caseId].
 *
 * [version] carries optimistic locking: the chat lane and a "Continue on Telegram" request can write the same
 * conversation at once.
 */
@Node("MessagingConversation")
data class MessagingConversationNode(
    @Id
    val id: String,
    val platform: String,
    val accountId: String,
    val chatId: String,
    val userId: String,
    val caseId: String? = null,
    val lastInboundMessageId: Long? = null,
    val conversationKey: String,
    // EntityMetadata fields
    @Version val version: Long? = null,
    @CreatedDate val created: Instant = Instant.now(),
    @CreatedBy val createdBy: String? = null,
    @LastModifiedDate val modified: Instant = Instant.now(),
    @LastModifiedBy val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(): MessagingConversation =
        MessagingConversation(
            metadata =
                EntityMetadata(
                    id = UUID.fromString(id),
                    created = created,
                    createdBy = createdBy,
                    modified = modified,
                    modifiedBy = modifiedBy,
                    removed = removed ?: false,
                    version = version,
                ),
            platform = MessagingPlatform.valueOf(platform),
            accountId = accountId,
            chatId = chatId,
            userId = UUID.fromString(userId),
            caseId = caseId?.let(UUID::fromString),
            lastInboundMessageId = lastInboundMessageId,
        )

    companion object {
        fun fromDomain(conversation: MessagingConversation): MessagingConversationNode =
            MessagingConversationNode(
                id = conversation.id.toString(),
                platform = conversation.platform.name,
                accountId = conversation.accountId,
                chatId = conversation.chatId,
                userId = conversation.userId.toString(),
                caseId = conversation.caseId?.toString(),
                lastInboundMessageId = conversation.lastInboundMessageId,
                conversationKey = conversation.conversationKey,
                version = conversation.metadata.version,
                created = conversation.metadata.created,
                createdBy = conversation.metadata.createdBy,
                modified = conversation.metadata.modified,
                modifiedBy = conversation.metadata.modifiedBy,
                removed = conversation.metadata.removed.takeIf { it },
            )
    }
}
