package io.whozoss.agentos.messaging

import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A [MessagingManagerAdapter] that records what it is asked to do instead of calling a platform. */
class FakeMessagingAdapter(
    override val bot: MessagingBotRef = botRef(),
) : MessagingManagerAdapter {
    data class Sent(
        val chatId: String,
        val message: OutboundMessage,
    )

    val sent = CopyOnWriteArrayList<Sent>()
    val typing = CopyOnWriteArrayList<String>()
    val acknowledged = CopyOnWriteArrayList<Pair<String, String?>>()
    val buttonsRemoved = CopyOnWriteArrayList<String>()
    val offers = CopyOnWriteArrayList<Pair<String, BotCreationOffer>>()

    /** The tokens of the bots this one manages, as the platform would give them. */
    val managedTokens = ConcurrentHashMap<String, String>()

    fun textsTo(chatId: String): List<String> = sent.filter { it.chatId == chatId }.map { it.message.text }

    fun clear() {
        sent.clear()
        typing.clear()
        acknowledged.clear()
        buttonsRemoved.clear()
        offers.clear()
    }

    override fun linkUrl(token: String): String = "https://t.me/${bot.username}?start=$token"

    override fun send(
        chatId: String,
        message: OutboundMessage,
    ) {
        sent.add(Sent(chatId, message))
    }

    override fun showTyping(chatId: String) {
        typing.add(chatId)
    }

    override fun acknowledgeClick(
        clickId: String,
        notice: String?,
    ) {
        acknowledged.add(clickId to notice)
    }

    override fun removeButtons(
        chatId: String,
        messageId: String,
    ) {
        buttonsRemoved.add(messageId)
    }

    override fun offerBotCreation(
        chatId: String,
        offer: BotCreationOffer,
    ) {
        offers.add(chatId to offer)
    }

    override fun managedBotToken(accountId: String): String =
        managedTokens[accountId] ?: throw MessagingDeliveryException("not a managed bot: $accountId")

    companion object {
        fun botRef(
            accountId: String = "4242",
            username: String = "fake_bot",
            agentId: UUID? = UUID.randomUUID(),
            namespaceId: UUID? = UUID.randomUUID(),
            role: MessagingBotRole = MessagingBotRole.AGENT,
        ) = MessagingBotRef(
            id = UUID.randomUUID(),
            platform = MessagingPlatform.TELEGRAM,
            role = role,
            accountId = accountId,
            username = username,
            agentId = agentId,
            namespaceId = namespaceId,
        )
    }
}
