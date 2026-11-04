package io.whozoss.agentos.messaging.telegram

import io.whozoss.agentos.messaging.ManagedBotInfo
import io.whozoss.agentos.messaging.MessagingActionCodec
import io.whozoss.agentos.messaging.MessagingInbound
import io.whozoss.agentos.messaging.MessagingSender
import io.whozoss.agentos.messaging.MessagingSink
import io.whozoss.agentos.messaging.SerialLanes
import io.whozoss.agentos.sdk.api.messaging.MessagingBotStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KLogging
import java.io.IOException
import kotlin.math.min

/**
 * Receives the updates of one Telegram bot by long polling (`getUpdates`) and hands them to [sink], from
 * [start] to [stop].
 *
 * Long polling needs no public URL: the instance reaches Telegram, never the other way round, which suits an
 * instance behind an access proxy. Telegram serves one poller per token: a second program polling the same bot
 * gets `409 Conflict` until the first stops, which [onStatus] reports as [MessagingBotStatus.CONFLICT].
 *
 * The updates of one chat are handled in order, on a lane of their own ([SerialLanes]); different chats in
 * parallel. An update is confirmed to Telegram (the offset of the next poll) once queued: one lost in a crash
 * before it was handled is not delivered again, one delivered again after a crash before the confirmation is
 * recognized by [io.whozoss.agentos.messaging.MessagingConversation.lastInboundMessageId].
 */
class TelegramUpdatePoller(
    private val client: TelegramBotClient,
    private val adapter: TelegramAdapter,
    private val sink: MessagingSink,
    private val pollTimeoutSeconds: Int,
    private val allowedUpdates: List<String>,
    /** The command menu Telegram shows in the chat with the bot; empty sets none. */
    private val commands: Map<String, String>,
    private val onStatus: (MessagingBotStatus) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lanes = SerialLanes<String>(scope, name = "Telegram @${adapter.bot.username}")
    private val name = "@${adapter.bot.username}"

    fun start() {
        scope.launch { poll() }
    }

    fun stop() {
        scope.cancel()
        client.cancelPoll()
    }

    private suspend fun poll() {
        prepare()
        var offset: Long? = null
        var failures = 0
        while (currentCoroutineContext().isActive) {
            try {
                client.getUpdates(offset, pollTimeoutSeconds, allowedUpdates).forEach { update ->
                    toInbound(update)?.let { inbound -> lanes.submit(inbound.chatId) { sink.handle(adapter, inbound) } }
                    offset = update.updateId + 1
                }
                failures = 0
                onStatus(MessagingBotStatus.RUNNING)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TelegramApiException) {
                failures++
                when (e.errorCode) {
                    UNAUTHORIZED -> {
                        logger.error { "[Telegram] $name: token rejected, the bot stops until it is attached again" }
                        onStatus(MessagingBotStatus.TOKEN_REJECTED)
                        currentCoroutineContext().cancel()
                    }
                    CONFLICT -> {
                        logger.warn { "[Telegram] $name: another program receives this bot's updates (${e.description})" }
                        onStatus(MessagingBotStatus.CONFLICT)
                        delay(CONFLICT_WAIT_MILLIS)
                    }
                    else -> {
                        logger.warn { "[Telegram] $name: getUpdates failed: ${e.errorCode} ${e.description}" }
                        onStatus(MessagingBotStatus.UNREACHABLE)
                        delay(e.retryAfterSeconds?.let { it * 1000L } ?: backoff(failures))
                    }
                }
            } catch (e: IOException) {
                failures++
                if (currentCoroutineContext().isActive) {
                    logger.warn { "[Telegram] $name: getUpdates failed (${e.javaClass.simpleName}), retrying" }
                    onStatus(MessagingBotStatus.UNREACHABLE)
                    delay(backoff(failures))
                }
            }
        }
    }

    /** Sets the bot's command menu, retrying until Telegram answers; a rejected token stops the bot. */
    private suspend fun prepare() {
        var failures = 0
        var prepared = commands.isEmpty()
        while (!prepared && currentCoroutineContext().isActive) {
            prepared =
                try {
                    client.setMyCommands(commands)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TelegramApiException) {
                    failures++
                    when (e.errorCode) {
                        UNAUTHORIZED -> {
                            logger.error { "[Telegram] $name: token rejected, the bot stops until it is attached again" }
                            onStatus(MessagingBotStatus.TOKEN_REJECTED)
                            currentCoroutineContext().cancel()
                        }
                        else -> {
                            logger.warn { "[Telegram] $name: setMyCommands failed (${e.errorCode} ${e.description}), retrying" }
                            onStatus(MessagingBotStatus.UNREACHABLE)
                            delay(backoff(failures))
                        }
                    }
                    false
                } catch (e: IOException) {
                    failures++
                    logger.warn { "[Telegram] $name: not reachable yet (${e.javaClass.simpleName}), retrying" }
                    onStatus(MessagingBotStatus.UNREACHABLE)
                    delay(backoff(failures))
                    false
                }
        }
        if (prepared) logger.info { "[Telegram] polling as $name" }
    }

    private fun toInbound(update: TelegramUpdate): MessagingInbound? {
        val message = update.message
        val click = update.callbackQuery
        val managed = update.managedBot
        return when {
            message?.from != null && message.text != null ->
                MessagingInbound.Text(
                    chatId = message.chat.id.toString(),
                    sender = sender(message.from),
                    messageId = message.messageId,
                    text = message.text,
                    direct = message.chat.private,
                )
            click?.message != null ->
                MessagingInbound.Click(
                    chatId = click.message.chat.id.toString(),
                    sender = sender(click.from),
                    clickId = click.id,
                    messageId = click.message.messageId.toString(),
                    action = MessagingActionCodec.decode(click.data),
                )
            // The private chat of a user with a bot has the user's id.
            managed != null ->
                MessagingInbound.BotManaged(
                    chatId = managed.user.id.toString(),
                    sender = sender(managed.user),
                    bot =
                        ManagedBotInfo(
                            accountId = managed.bot.id.toString(),
                            username = managed.bot.username.orEmpty(),
                            displayName = managed.bot.displayName,
                        ),
                )
            else -> null
        }
    }

    private fun sender(user: TelegramUser): MessagingSender =
        MessagingSender(externalUserId = user.id.toString(), displayName = user.displayName, bot = user.bot)

    private fun backoff(failures: Int): Long = min(MAX_BACKOFF_MILLIS, BASE_BACKOFF_MILLIS shl min(failures, 5))

    companion object : KLogging() {
        private const val UNAUTHORIZED = 401
        private const val CONFLICT = 409
        private const val BASE_BACKOFF_MILLIS = 1000L
        private const val MAX_BACKOFF_MILLIS = 60_000L
        private const val CONFLICT_WAIT_MILLIS = 30_000L
    }
}
