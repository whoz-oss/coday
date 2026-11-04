package io.whozoss.agentos.messaging.slack

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.messaging.AppManifest
import io.whozoss.agentos.messaging.MessagingBot
import io.whozoss.agentos.messaging.MessagingConnection
import io.whozoss.agentos.messaging.MessagingDeliveryException
import io.whozoss.agentos.messaging.MessagingDriver
import io.whozoss.agentos.messaging.MessagingInbound
import io.whozoss.agentos.messaging.MessagingProperties
import io.whozoss.agentos.messaging.MessagingSink
import io.whozoss.agentos.messaging.ProbedBot
import io.whozoss.agentos.messaging.SerialLanes
import io.whozoss.agentos.sdk.api.messaging.MessagingBotStatus
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.springframework.stereotype.Component
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The [MessagingDriver] of Slack. A bot is a Slack app of its own, which an admin creates from the manifest
 * AgentOS gives ([appManifest]) and installs in their workspace; the app has two tokens: the bot token
 * (`xoxb-`), to send and to know who the bot is, and the app-level token (`xapp-`, scope `connections:write`), to
 * receive by Socket Mode ([SlackSocket]).
 *
 * The account id of a bot is its workspace and bot user (`<team>.<user>`); its address opens the app's chat in
 * Slack. The updates of one chat are handled in order, on a lane of their own, after the sender's name is looked
 * up.
 */
@Component
class SlackDriver(
    properties: MessagingProperties,
    private val objectMapper: ObjectMapper,
) : MessagingDriver {
    override val platform: MessagingPlatform = MessagingPlatform.SLACK

    private val httpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .build()

    private val web = SlackWebClient(properties.slack.apiBaseUrl, httpClient, objectMapper)

    override fun probe(
        token: String,
        appToken: String?,
    ): ProbedBot {
        if (!token.startsWith(BOT_TOKEN_PREFIX)) {
            throw BadRequestException("This is not a Slack bot token: it starts with $BOT_TOKEN_PREFIX")
        }
        if (appToken == null || !appToken.startsWith(APP_TOKEN_PREFIX)) {
            throw BadRequestException("A Slack bot also needs its app-level token, which starts with $APP_TOKEN_PREFIX")
        }
        return slackCall("bot token") {
            val auth = web.authTest(token)
            val teamId = auth["team_id"].asText()
            val botUserId = auth["user_id"].asText()
            val bot = web.botsInfo(token, auth["bot_id"].asText())
            slackCall("app-level token") { web.openConnection(appToken) }
            ProbedBot(
                accountId = "$teamId$ACCOUNT_SEPARATOR$botUserId",
                username = auth["user"]?.asText().orEmpty().ifEmpty { botUserId },
                displayName = bot?.get("name")?.asText().orEmpty().ifEmpty { auth["user"]?.asText().orEmpty() },
                url = "https://slack.com/app_redirect?app=${bot?.get("app_id")?.asText()}&team=$teamId",
                canManageBots = false,
            )
        }
    }

    override fun appManifest(
        agentName: String,
        agentDescription: String?,
    ): AppManifest {
        val manifest =
            mapOf(
                "display_information" to
                    mapOf(
                        "name" to agentName.take(MAX_APP_NAME_LENGTH),
                        "description" to
                            (agentDescription?.takeIf { it.isNotBlank() } ?: "An AgentOS agent").take(MAX_DESCRIPTION_LENGTH),
                    ),
                "features" to
                    mapOf(
                        "app_home" to
                            mapOf(
                                "home_tab_enabled" to false,
                                "messages_tab_enabled" to true,
                                "messages_tab_read_only_enabled" to false,
                            ),
                        "bot_user" to mapOf("display_name" to displayName(agentName), "always_online" to true),
                    ),
                "oauth_config" to mapOf("scopes" to mapOf("bot" to BOT_SCOPES)),
                "settings" to
                    mapOf(
                        "event_subscriptions" to mapOf("bot_events" to BOT_EVENTS),
                        "interactivity" to mapOf("is_enabled" to true),
                        "org_deploy_enabled" to false,
                        "socket_mode_enabled" to true,
                        "token_rotation_enabled" to false,
                    ),
            )
        return AppManifest(
            manifest = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
            createUrl = CREATE_APP_URL,
        )
    }

    override fun start(
        bot: MessagingBot,
        sink: MessagingSink,
        onStatus: (MessagingBotStatus) -> Unit,
    ): MessagingConnection {
        val appToken = requireNotNull(bot.appToken) { "A Slack bot has an app-level token" }
        val botUserId = bot.accountId.substringAfter(ACCOUNT_SEPARATOR)
        val adapter = SlackAdapter(web = web, bot = bot.ref(), token = bot.token)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val lanes = SerialLanes<String>(scope, name = "Slack @${bot.username}")
        val socket =
            SlackSocket(
                appToken = appToken,
                web = web,
                httpClient = httpClient,
                objectMapper = objectMapper,
                name = "@${bot.username}",
                onEnvelope = { envelope ->
                    SlackEvents.inbound(envelope, botUserId)?.let { inbound ->
                        lanes.submit(inbound.chatId) { sink.handle(adapter, named(inbound, adapter)) }
                    }
                },
                onStatus = onStatus,
            )
        socket.start()
        return object : MessagingConnection {
            override val adapter = adapter

            override fun stop() {
                socket.stop()
                scope.cancel()
            }
        }
    }

    @PreDestroy
    fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    /** [inbound] with the name Slack shows for its sender, which events do not carry. */
    private fun named(
        inbound: MessagingInbound,
        adapter: SlackAdapter,
    ): MessagingInbound {
        val sender = inbound.sender.copy(displayName = adapter.displayName(inbound.sender.externalUserId))
        return when (inbound) {
            is MessagingInbound.Text -> inbound.copy(sender = sender)
            is MessagingInbound.Click -> inbound.copy(sender = sender)
            is MessagingInbound.BotManaged -> inbound
        }
    }

    /** Slack's bot display names allow `a-z 0-9 - _ .` only. */
    private fun displayName(agentName: String): String =
        agentName
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .ifEmpty { "agentos-agent" }
            .take(MAX_DISPLAY_NAME_LENGTH)

    private fun <T> slackCall(
        what: String,
        call: () -> T,
    ): T =
        try {
            call()
        } catch (e: SlackApiException) {
            when {
                e.tokenRejected -> throw BadRequestException("Slack rejected the $what: ${e.error}")
                else -> throw MessagingDeliveryException(e.error, e)
            }
        } catch (e: IOException) {
            throw MessagingDeliveryException("Slack cannot be reached", e)
        }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val PING_INTERVAL_SECONDS = 30L
        private const val BOT_TOKEN_PREFIX = "xoxb-"
        private const val APP_TOKEN_PREFIX = "xapp-"
        private const val ACCOUNT_SEPARATOR = "."
        private const val MAX_APP_NAME_LENGTH = 35
        private const val MAX_DESCRIPTION_LENGTH = 140
        private const val MAX_DISPLAY_NAME_LENGTH = 80
        private const val CREATE_APP_URL = "https://api.slack.com/apps?new_app=1"

        /** What the app may do: write, read and open its direct chats, and name the people who write to it. */
        private val BOT_SCOPES = listOf("chat:write", "im:history", "im:read", "im:write", "users:read")

        /** The messages of the app's direct chats. */
        private val BOT_EVENTS = listOf("message.im")
    }
}
