package io.whozoss.agentos.messaging

import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import mu.KLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Finds the conversation of a chat and moves it from one case to another.
 *
 * Several writers touch the same conversation: the chat's own messages, a "Continue on Telegram" request from
 * the UI, a `/cases` choice. Conversations are versioned, so a write based on a stale read is refused and
 * [update] applies its change again to a fresh copy.
 */
@Service
class MessagingConversationService(
    private val repository: MessagingConversationRepository,
) {
    fun findById(id: UUID): MessagingConversation? = repository.findById(id)

    /** The active conversations whose next message goes to [caseId]. */
    fun findByCase(caseId: UUID): List<MessagingConversation> = repository.findByCaseId(caseId)

    /**
     * The conversation of the chat [chatId], created for [userId] on first use. A chat whose account was linked
     * to another user since is handed to [userId] with no case, so that the new owner never lands in a case
     * of the previous one.
     */
    fun open(
        platform: MessagingPlatform,
        accountId: String,
        chatId: String,
        userId: UUID,
    ): MessagingConversation {
        val key = MessagingConversation.conversationKey(platform, accountId, chatId)
        val conversation =
            repository.findByConversationKey(key)
                ?: createOrFind(
                    MessagingConversation(platform = platform, accountId = accountId, chatId = chatId, userId = userId),
                )
        return when (conversation.userId) {
            userId -> conversation
            else -> update(conversation) { it.copy(userId = userId, caseId = null) }
        }
    }

    /**
     * Saves [change] applied to [conversation], the copy last read. On a concurrent write the conversation is
     * read again and [change] applied to that copy, up to [ATTEMPTS] saves: [change] must be side-effect-free.
     */
    fun update(
        conversation: MessagingConversation,
        change: (MessagingConversation) -> MessagingConversation,
    ): MessagingConversation = updateAttempt(conversation, change, ATTEMPTS)

    private fun updateAttempt(
        current: MessagingConversation,
        change: (MessagingConversation) -> MessagingConversation,
        attemptsLeft: Int,
    ): MessagingConversation =
        try {
            repository.save(change(current))
        } catch (e: OptimisticLockingFailureException) {
            when {
                attemptsLeft > 1 -> {
                    logger.debug { "[Messaging] conversation ${current.id} written concurrently, applying the change again" }
                    updateAttempt(reread(current), change, attemptsLeft - 1)
                }
                else -> throw e
            }
        }

    /** A chat opened twice at once: the constraint on the active key lets one creation win, the other reads it. */
    private fun createOrFind(conversation: MessagingConversation): MessagingConversation =
        try {
            repository.save(conversation)
        } catch (e: DataIntegrityViolationException) {
            logger.debug { "[Messaging] conversation ${conversation.conversationKey} created concurrently, reading it" }
            repository.findByConversationKey(conversation.conversationKey) ?: throw e
        }

    private fun reread(conversation: MessagingConversation): MessagingConversation =
        checkNotNull(repository.findById(conversation.id)) { "Conversation ${conversation.id} disappeared during an update" }

    companion object : KLogging() {
        /** Saves of one change before a concurrent write is reported to the caller. */
        private const val ATTEMPTS = 3
    }
}
