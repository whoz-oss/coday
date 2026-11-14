package io.whozoss.agentos.messaging.slack

import io.whozoss.agentos.messaging.MarkdownBlocks
import io.whozoss.agentos.messaging.MessagingAction
import io.whozoss.agentos.messaging.MessagingActionCodec
import io.whozoss.agentos.messaging.MessagingAdapter
import io.whozoss.agentos.messaging.MessagingAudio
import io.whozoss.agentos.messaging.MessagingBotRef
import io.whozoss.agentos.messaging.MessagingButton
import io.whozoss.agentos.messaging.MessagingCommand
import io.whozoss.agentos.messaging.MessagingDeliveryException
import io.whozoss.agentos.messaging.OutboundMessage
import mu.KLogging
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The [MessagingAdapter] of one Slack app, sending with its bot token.
 *
 * Messages go out as a `markdown` block, which renders the Markdown agents write (tables and code included), with
 * their text as the notification fallback; buttons as an `actions` block, with Slack's confirmation dialog on a
 * button that asks for one ([MessagingButton.confirm]). A message Slack refuses as blocks goes again as plain text
 * without buttons; a rate limit is waited out, a bounded number of times.
 *
 * Slack keeps `/` for its own commands: the gateway's start with `!`. A link to the app's chat cannot carry a link
 * token: the user sends it as a message ([linkCode]). Slack shows no typing indicator for a bot outside its AI
 * assistant threads: [showTyping] does nothing. A click is answered by an ephemeral message when it was refused,
 * since a successful one shows by itself.
 */
class SlackAdapter(
    private val web: SlackWebClient,
    override val bot: MessagingBotRef,
    private val token: String,
) : MessagingAdapter {
    override val commandPrefix: String = MessagingCommand.BANG

    private val names = ConcurrentHashMap<String, String>()

    /** The text of the recent messages sent with buttons, by timestamp: what is left once the buttons are removed. */
    private val withButtons = Collections.synchronizedMap(LruMap<String, String>(MAX_REMEMBERED_MESSAGES))

    override fun linkUrl(token: String): String = bot.url

    override fun linkCode(token: String): String = MessagingCommand.linkMessage(token)

    /** The name Slack shows for [userId], asked once. Its id when Slack cannot say. */
    fun displayName(userId: String): String =
        names[userId] ?: try {
            web.userName(token, userId).also { names[userId] = it }
        } catch (e: SlackApiException) {
            logger.debug { "[Slack] users.info failed for $userId: ${e.error}" }
            userId
        } catch (e: IOException) {
            userId
        }

    override fun send(
        chatId: String,
        message: OutboundMessage,
    ) {
        val chunks = MarkdownBlocks.split(message.text, MAX_CHUNK_LENGTH)
        chunks.forEachIndexed { index, chunk ->
            sendChunk(chatId, chunk, message.buttons.takeIf { index == chunks.lastIndex }.orEmpty())
        }
    }

    override fun showTyping(chatId: String) = Unit

    /** [audio] by its URL; by its file id when the event carried none, which `files.info` then gives. */
    override fun downloadAudio(
        audio: MessagingAudio,
        maxBytes: Long,
    ): ByteArray =
        try {
            val url =
                audio.ref.takeIf { it.startsWith("https://") }
                    ?: web.fileInfo(token, audio.ref)?.let { it["url_private_download"] ?: it["url_private"] }?.asText()
                    ?: throw MessagingDeliveryException("Slack gave no URL to download the voice message")
            web.download(token, url, maxBytes)
        } catch (e: SlackApiException) {
            throw MessagingDeliveryException("Slack refused the voice message: ${e.error}", e)
        } catch (e: IOException) {
            throw MessagingDeliveryException("Slack could not be reached for the voice message", e)
        }

    override fun acknowledgeClick(
        clickId: String,
        notice: String?,
    ) {
        val channel = clickId.substringBefore(':')
        val user = clickId.substringAfter(':')
        if (notice != null && !notice.startsWith(SUCCESS_MARK)) {
            bestEffort("click notice") { web.postEphemeral(token, channel, user, notice) }
        }
    }

    override fun removeButtons(
        chatId: String,
        messageId: String,
    ) {
        val text = withButtons.remove(messageId) ?: return
        bestEffort("button removal") { web.updateMessage(token, chatId, messageId, text, listOf(markdown(text))) }
    }

    private fun sendChunk(
        chatId: String,
        markdown: String,
        buttons: List<MessagingButton>,
    ) {
        val blocks = listOf(markdown(markdown)) + listOfNotNull(actions(buttons))
        try {
            val ts = withRateLimitRetry { web.postMessage(token, chatId, markdown, blocks) }
            if (buttons.isNotEmpty()) withButtons[ts] = markdown
        } catch (e: SlackApiException) {
            when (e.error) {
                "invalid_blocks", "invalid_blocks_format" -> {
                    logger.debug { "[Slack] message refused as blocks (${e.error}), sending it as plain text" }
                    plain { withRateLimitRetry { web.postMessage(token, chatId, markdown, null) } }
                }
                else -> throw MessagingDeliveryException(e.error, e)
            }
        } catch (e: IOException) {
            throw MessagingDeliveryException("Slack cannot be reached", e)
        }
    }

    private fun plain(send: () -> String) {
        try {
            send()
        } catch (e: SlackApiException) {
            throw MessagingDeliveryException(e.error, e)
        }
    }

    private fun markdown(text: String): Map<String, Any?> = mapOf("type" to "markdown", "text" to text)

    private fun actions(buttons: List<MessagingButton>): Map<String, Any?>? =
        buttons
            .take(MAX_BUTTONS)
            .mapIndexedNotNull { index, button ->
                val label = mapOf("type" to "plain_text", "text" to button.label.take(MAX_LABEL_LENGTH), "emoji" to true)
                when (val action = button.action) {
                    is MessagingAction.OpenUrl ->
                        mapOf("type" to "button", "text" to label, "url" to action.url, "action_id" to "url_$index")
                    else ->
                        MessagingActionCodec.encode(action)?.let { value ->
                            mapOf("type" to "button", "text" to label, "value" to value, "action_id" to "action_$index") +
                                button.confirm?.let { mapOf("confirm" to confirmation(button.label, it)) }.orEmpty()
                        }
                }
            }.takeIf { it.isNotEmpty() }
            ?.let { elements -> mapOf("type" to "actions", "elements" to elements) }

    /** Slack's confirmation dialog: [text] with a button that sends the click, labelled like the button, and Cancel. */
    private fun confirmation(
        label: String,
        text: String,
    ): Map<String, Any?> {
        fun plain(value: String) = mapOf("type" to "plain_text", "text" to value)
        return mapOf(
            "title" to plain("Are you sure?"),
            "text" to plain(text.take(MAX_CONFIRM_TEXT_LENGTH)),
            "confirm" to plain(label.take(MAX_CONFIRM_LABEL_LENGTH)),
            "deny" to plain("Cancel"),
        )
    }

    private fun <T> withRateLimitRetry(send: () -> T): T = rateLimitAttempt(send, RATE_LIMIT_ATTEMPTS)

    private fun <T> rateLimitAttempt(
        send: () -> T,
        attemptsLeft: Int,
    ): T =
        try {
            send()
        } catch (e: SlackApiException) {
            val wait =
                (e.retryAfterSeconds ?: 1).takeIf { e.error == "ratelimited" && attemptsLeft > 1 && it <= MAX_RATE_LIMIT_WAIT_SECONDS }
            when (wait) {
                null -> throw e
                else -> {
                    logger.info { "[Slack] rate limited, waiting ${wait}s before sending again" }
                    Thread.sleep(wait * 1000L)
                    rateLimitAttempt(send, attemptsLeft - 1)
                }
            }
        }

    private fun bestEffort(
        what: String,
        call: () -> Unit,
    ) {
        try {
            call()
        } catch (e: SlackApiException) {
            logger.debug { "[Slack] $what failed: ${e.error}" }
        } catch (e: IOException) {
            logger.debug { "[Slack] $what failed: ${e.javaClass.simpleName}" }
        }
    }

    /** A map that forgets its oldest entry beyond [capacity]. */
    private class LruMap<K, V>(
        private val capacity: Int,
    ) : LinkedHashMap<K, V>(capacity, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity

        private companion object {
            const val LOAD_FACTOR = 0.75f
        }
    }

    companion object : KLogging() {
        /** Slack caps the markdown blocks of one message at 12,000 characters: one chunk per message stays far below. */
        private const val MAX_CHUNK_LENGTH = 3500
        private const val MAX_BUTTONS = 25
        private const val MAX_LABEL_LENGTH = 75
        private const val MAX_CONFIRM_TEXT_LENGTH = 300
        private const val MAX_CONFIRM_LABEL_LENGTH = 30
        private const val MAX_REMEMBERED_MESSAGES = 500
        private const val RATE_LIMIT_ATTEMPTS = 3
        private const val SUCCESS_MARK = "✓"

        /** A longer wait is reported as a failure: holding a chat's lane longer would stall its replies. */
        private const val MAX_RATE_LIMIT_WAIT_SECONDS = 30
    }
}
