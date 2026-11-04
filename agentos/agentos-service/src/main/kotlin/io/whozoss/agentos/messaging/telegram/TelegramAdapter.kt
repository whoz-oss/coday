package io.whozoss.agentos.messaging.telegram

import io.whozoss.agentos.messaging.BotCreationOffer
import io.whozoss.agentos.messaging.MessagingAction
import io.whozoss.agentos.messaging.MessagingActionCodec
import io.whozoss.agentos.messaging.MessagingBotRef
import io.whozoss.agentos.messaging.MessagingButton
import io.whozoss.agentos.messaging.MessagingDeliveryException
import io.whozoss.agentos.messaging.MessagingManagerAdapter
import io.whozoss.agentos.messaging.OutboundMessage
import mu.KLogging
import java.io.IOException

/**
 * The [MessagingAdapter] of one Telegram bot, the bot of an agent or a bot manager.
 *
 * Messages go out as Telegram HTML; one Telegram refuses (a parse error, a link button to a host it does not
 * accept, such as `localhost`) goes again as plain text without link buttons. A refusal for flooding is waited
 * out, a bounded number of times.
 */
class TelegramAdapter(
    private val client: TelegramBotClient,
    override val bot: MessagingBotRef,
) : MessagingManagerAdapter {
    override fun linkUrl(token: String): String = "${TelegramDriver.urlOf(bot.username)}?start=$token"

    /** A reply keyboard of one button, which opens Telegram's bot creation and hides once used. */
    override fun offerBotCreation(
        chatId: String,
        offer: BotCreationOffer,
    ) {
        val button =
            mapOf(
                "text" to offer.buttonLabel,
                "request_managed_bot" to
                    mapOf(
                        "request_id" to BOT_CREATION_REQUEST_ID,
                        "suggested_name" to offer.suggestedName,
                        "suggested_username" to offer.suggestedUsername,
                    ),
            )
        val keyboard = mapOf("keyboard" to listOf(listOf(button)), "resize_keyboard" to true, "one_time_keyboard" to true)
        try {
            withFloodRetry { client.sendMessage(chatId, TelegramFormatter.toHtml(offer.text), "HTML", keyboard) }
        } catch (e: TelegramApiException) {
            throw MessagingDeliveryException(e.description, e)
        } catch (e: IOException) {
            throw MessagingDeliveryException("Telegram cannot be reached", e)
        }
    }

    override fun managedBotToken(accountId: String): String =
        try {
            client.getManagedBotToken(accountId.toLong())
        } catch (e: TelegramApiException) {
            throw MessagingDeliveryException(e.description, e)
        } catch (e: IOException) {
            throw MessagingDeliveryException("Telegram cannot be reached", e)
        }

    override fun send(
        chatId: String,
        message: OutboundMessage,
    ) {
        val chunks = TelegramFormatter.split(message.text)
        chunks.forEachIndexed { index, chunk ->
            sendChunk(chatId, chunk, message.buttons.takeIf { index == chunks.lastIndex }.orEmpty())
        }
    }

    override fun showTyping(chatId: String) {
        bestEffort("typing indicator") { client.sendChatAction(chatId, "typing") }
    }

    override fun acknowledgeClick(
        clickId: String,
        notice: String?,
    ) {
        bestEffort("click acknowledgement") { client.answerCallbackQuery(clickId, notice) }
    }

    override fun removeButtons(
        chatId: String,
        messageId: String,
    ) {
        bestEffort("button removal") { messageId.toLongOrNull()?.let { client.removeInlineKeyboard(chatId, it) } }
    }

    private fun sendChunk(
        chatId: String,
        markdown: String,
        buttons: List<MessagingButton>,
    ) {
        val html = TelegramFormatter.toHtml(markdown).takeIf { it.length <= TelegramFormatter.MAX_MESSAGE_LENGTH }
        try {
            when (html) {
                null -> sendPlain(chatId, markdown, buttons)
                else -> withFloodRetry { client.sendMessage(chatId, html, "HTML", keyboard(buttons)) }
            }
        } catch (e: TelegramApiException) {
            when (e.errorCode) {
                BAD_REQUEST -> {
                    logger.debug { "[Telegram] message refused as HTML (${e.description}), sending it as plain text" }
                    sendPlain(chatId, markdown, buttons.filter { it.action !is MessagingAction.OpenUrl })
                }
                else -> throw MessagingDeliveryException(e.description, e)
            }
        } catch (e: IOException) {
            throw MessagingDeliveryException("Telegram cannot be reached", e)
        }
    }

    private fun sendPlain(
        chatId: String,
        text: String,
        buttons: List<MessagingButton>,
    ) {
        val parts = text.chunked(TelegramFormatter.MAX_MESSAGE_LENGTH)
        try {
            parts.forEachIndexed { index, part ->
                val markup = keyboard(buttons.takeIf { index == parts.lastIndex }.orEmpty())
                withFloodRetry { client.sendMessage(chatId, part, null, markup) }
            }
        } catch (e: TelegramApiException) {
            throw MessagingDeliveryException(e.description, e)
        }
    }

    /** One button per row: options are sentences more often than words. */
    private fun keyboard(buttons: List<MessagingButton>): Map<String, Any>? =
        buttons
            .mapNotNull { button ->
                when (val action = button.action) {
                    is MessagingAction.OpenUrl -> mapOf("text" to button.label, "url" to action.url)
                    else -> MessagingActionCodec.encode(action)?.let { mapOf("text" to button.label, "callback_data" to it) }
                }
            }.takeIf { it.isNotEmpty() }
            ?.let { row -> mapOf("inline_keyboard" to row.map { listOf(it) }) }

    private fun <T> withFloodRetry(send: () -> T): T = floodRetryAttempt(send, FLOOD_ATTEMPTS)

    private fun <T> floodRetryAttempt(
        send: () -> T,
        attemptsLeft: Int,
    ): T =
        try {
            send()
        } catch (e: TelegramApiException) {
            val wait =
                e.retryAfterSeconds?.takeIf { e.errorCode == TOO_MANY_REQUESTS && attemptsLeft > 1 && it <= MAX_FLOOD_WAIT_SECONDS }
            when (wait) {
                null -> throw e
                else -> {
                    logger.info { "[Telegram] flood control, waiting ${wait}s before sending again" }
                    Thread.sleep(wait * 1000L)
                    floodRetryAttempt(send, attemptsLeft - 1)
                }
            }
        }

    private fun bestEffort(
        what: String,
        call: () -> Unit,
    ) {
        try {
            call()
        } catch (e: TelegramApiException) {
            logger.debug { "[Telegram] $what failed: ${e.description}" }
        } catch (e: IOException) {
            logger.debug { "[Telegram] $what failed: ${e.javaClass.simpleName}" }
        }
    }

    companion object : KLogging() {
        /** Telegram wants an id per button of a message; the offer has one button. */
        private const val BOT_CREATION_REQUEST_ID = 1
        private const val BAD_REQUEST = 400
        private const val TOO_MANY_REQUESTS = 429
        private const val FLOOD_ATTEMPTS = 3

        /** A longer wait is reported as a failure: holding a chat's lane longer would stall its replies. */
        private const val MAX_FLOOD_WAIT_SECONDS = 30
    }
}
