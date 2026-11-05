package io.whozoss.agentos.messaging

import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus
import java.util.UUID

/**
 * One running bot on one messaging platform, as [MessagingGateway] and [MessagingDelivery] use it: what they
 * send and how a user is sent to the bot. Receiving is the driver's business ([MessagingDriver.start]); it hands
 * what it receives to the bot's [MessagingSink].
 *
 * Calls block on the network: callers run them off the case threads.
 */
interface MessagingAdapter {
    /** The bot this adapter sends as. */
    val bot: MessagingBotRef

    val platform: MessagingPlatform get() = bot.platform

    /** Id of the bot account, part of every identity and conversation key. */
    val accountId: String get() = bot.accountId

    /**
     * The URL that opens the chat with the bot, given to a user who links an account: with [token] as its start
     * parameter where the platform's links carry one.
     */
    fun linkUrl(token: String): String

    /** The message to send to the bot to link an account, on a platform whose links cannot carry [token]; null elsewhere. */
    fun linkCode(token: String): String? = null

    /** What starts a gateway command in the platform's chats: `/`, unless the platform keeps it for its own commands. */
    val commandPrefix: String get() = MessagingCommand.SLASH

    /**
     * Sends [message] to [chatId], split into several messages when the platform needs it.
     *
     * @throws MessagingDeliveryException when the platform refuses the message
     */
    fun send(
        chatId: String,
        message: OutboundMessage,
    )

    /** Shows that the bot is writing in [chatId]. Best effort: a failure is ignored. */
    fun showTyping(chatId: String)

    /** Answers a button click so that the application stops waiting, with an optional short [notice]. */
    fun acknowledgeClick(
        clickId: String,
        notice: String? = null,
    )

    /**
     * The bytes of [audio], a voice message the bot received.
     *
     * @throws MessagingDeliveryException when the platform refuses, cannot be reached, or the audio is larger than
     *   [maxBytes]
     */
    fun downloadAudio(
        audio: MessagingAudio,
        maxBytes: Long,
    ): ByteArray

    /** Removes the buttons of the message [messageId] once one of them was used. Best effort. */
    fun removeButtons(
        chatId: String,
        messageId: String,
    )
}

/**
 * The extra calls of a bot manager ([MessagingBotRole.MANAGER]): it offers a user to create a bot it will
 * manage, then fetches that bot's token, so that nobody copies one.
 */
interface MessagingManagerAdapter : MessagingAdapter {
    /** Sends [offer] to [chatId], with the button that opens the platform's bot creation. */
    fun offerBotCreation(
        chatId: String,
        offer: BotCreationOffer,
    )

    /**
     * The token of the bot [accountId], which this bot manages.
     *
     * @throws MessagingDeliveryException when the platform refuses or cannot be reached
     */
    fun managedBotToken(accountId: String): String
}

/** What a bot manager proposes to create: the user may change the name and username before confirming. */
data class BotCreationOffer(
    val text: String,
    val buttonLabel: String,
    val suggestedName: String,
    val suggestedUsername: String,
)

/** A message to send, in Markdown: the adapter converts it to what its platform renders. */
data class OutboundMessage(
    val text: String,
    val buttons: List<MessagingButton> = emptyList(),
)

data class MessagingButton(
    val label: String,
    val action: MessagingAction,
    /** Asked before the click is sent, where the platform has a confirmation dialog (Slack); ignored elsewhere. */
    val confirm: String? = null,
)

/** What a button does when clicked. Encoded into the button by [MessagingActionCodec]. */
sealed interface MessagingAction {
    /** Answers the question [questionId] with its option at [optionIndex]. */
    data class AnswerQuestion(
        val questionId: UUID,
        val optionIndex: Int,
    ) : MessagingAction

    /** Moves the chat to the case [caseId]. */
    data class OpenCase(
        val caseId: UUID,
    ) : MessagingAction

    /** Applies the configuration plan the question [questionId] asks about, as the configuration card does. */
    data class ApplyPlan(
        val questionId: UUID,
    ) : MessagingAction

    /** Rejects the configuration plan the question [questionId] asks about. */
    data class RejectPlan(
        val questionId: UUID,
    ) : MessagingAction

    /** Opens [url] in the browser. Handled by the platform, never sent back to AgentOS. */
    data class OpenUrl(
        val url: String,
    ) : MessagingAction
}

/**
 * The platform refused a message or could not be reached: the reason is the platform's, never the content of the
 * message. Reaches an API caller as `502 Bad Gateway`.
 */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
class MessagingDeliveryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
