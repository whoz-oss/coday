package io.whozoss.agentos.messaging.telegram

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.messaging.MessagingBot
import io.whozoss.agentos.messaging.MessagingBotRole
import io.whozoss.agentos.messaging.MessagingConnection
import io.whozoss.agentos.messaging.MessagingDeliveryException
import io.whozoss.agentos.messaging.MessagingDriver
import io.whozoss.agentos.messaging.MessagingProperties
import io.whozoss.agentos.messaging.MessagingSink
import io.whozoss.agentos.messaging.ProbedBot
import io.whozoss.agentos.sdk.api.messaging.MessagingBotStatus
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import jakarta.annotation.PreDestroy
import okhttp3.OkHttpClient
import org.springframework.stereotype.Component
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The [MessagingDriver] of Telegram: checks a bot token with `getMe`, and runs a bot with a [TelegramAdapter]
 * fed by a [TelegramUpdatePoller].
 *
 * Every bot has its own client, since the token is part of every URL, over one HTTP client shared by all: its
 * read timeout outlasts the long poll. That HTTP client is not a bean: a second [OkHttpClient] bean would make
 * the injection of the existing one ambiguous.
 */
@Component
class TelegramDriver(
    private val properties: MessagingProperties,
    private val objectMapper: ObjectMapper,
) : MessagingDriver {
    override val platform: MessagingPlatform = MessagingPlatform.TELEGRAM

    private val httpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(properties.telegram.pollTimeoutSeconds + READ_MARGIN_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    override fun probe(token: String): ProbedBot {
        if (!TOKEN.matches(token)) throw BadRequestException("This is not a Telegram bot token")
        val me =
            try {
                client(token).getMe()
            } catch (e: TelegramApiException) {
                when (e.errorCode) {
                    UNAUTHORIZED, NOT_FOUND -> throw BadRequestException("Telegram rejected this token: ${e.description}")
                    else -> throw MessagingDeliveryException(e.description, e)
                }
            } catch (e: IOException) {
                throw MessagingDeliveryException("Telegram cannot be reached", e)
            }
        if (!me.bot || me.username.isNullOrBlank()) throw BadRequestException("This token is not a bot's")
        return ProbedBot(
            accountId = me.id.toString(),
            username = me.username,
            displayName = me.displayName,
            canManageBots = me.canManageBots,
        )
    }

    override fun botUrl(username: String): String = urlOf(username)

    override fun start(
        bot: MessagingBot,
        sink: MessagingSink,
        onStatus: (MessagingBotStatus) -> Unit,
    ): MessagingConnection {
        val client = client(bot.token)
        val adapter = TelegramAdapter(client = client, bot = bot.ref())
        val poller =
            TelegramUpdatePoller(
                client = client,
                adapter = adapter,
                sink = sink,
                pollTimeoutSeconds = properties.telegram.pollTimeoutSeconds,
                allowedUpdates =
                    when (bot.role) {
                        MessagingBotRole.AGENT -> AGENT_UPDATES
                        MessagingBotRole.MANAGER -> MANAGER_UPDATES
                    },
                commands =
                    when (bot.role) {
                        MessagingBotRole.AGENT -> AGENT_COMMANDS
                        MessagingBotRole.MANAGER -> emptyMap()
                    },
                onStatus = onStatus,
            )
        poller.start()
        return object : MessagingConnection {
            override val adapter = adapter

            override fun stop() = poller.stop()
        }
    }

    @PreDestroy
    fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun client(token: String): TelegramBotClient =
        TelegramBotClient(
            token = token,
            apiBaseUrl = properties.telegram.apiBaseUrl,
            httpClient = httpClient,
            objectMapper = objectMapper,
        )

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val WRITE_TIMEOUT_SECONDS = 30L
        private const val UNAUTHORIZED = 401
        private const val NOT_FOUND = 404

        /** Beyond the long poll's own timeout, for Telegram to answer and the network to carry it. */
        private const val READ_MARGIN_SECONDS = 15L

        /** `<bot id>:<secret>`, as BotFather and getManagedBotToken issue them. */
        private val TOKEN = Regex("""^\d+:[A-Za-z0-9_-]{30,}$""")

        /** The updates each kind of bot handles; Telegram does not send the others. */
        private val AGENT_UPDATES = listOf("message", "callback_query")
        private val MANAGER_UPDATES = listOf("message", "managed_bot")

        /** The menu Telegram shows in the chat with the bot of an agent; the gateway handles these commands. */
        private val AGENT_COMMANDS =
            linkedMapOf(
                "new" to "Start a new case with your next message",
                "cases" to "Continue one of your recent cases here",
                "case" to "Show the current case and its link",
                "help" to "Show the help",
            )

        /** The address that opens the bot [username] in Telegram. */
        fun urlOf(username: String): String = "https://t.me/$username"
    }
}
