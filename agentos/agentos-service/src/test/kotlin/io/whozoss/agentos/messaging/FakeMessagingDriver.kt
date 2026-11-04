package io.whozoss.agentos.messaging

import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.sdk.api.messaging.MessagingBotStatus
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import java.util.concurrent.ConcurrentHashMap

/**
 * A [MessagingDriver] standing for a platform, Telegram by default: it knows the bots registered with [register],
 * runs each started bot on a [FakeMessagingAdapter], and lets a test hand a bot what it would receive
 * ([receive]). Standing for Slack, it wants an app token, as the real one does.
 */
open class FakeMessagingDriver(
    override val platform: MessagingPlatform = MessagingPlatform.TELEGRAM,
) : MessagingDriver {
    override val supportsBotManagers: Boolean = platform == MessagingPlatform.TELEGRAM

    private val known = ConcurrentHashMap<String, ProbedBot>()
    private val running = ConcurrentHashMap<String, Running>()

    /** Makes [token] the token of a bot the platform knows. */
    fun register(
        token: String,
        accountId: String,
        username: String,
        canManageBots: Boolean = false,
    ): ProbedBot =
        ProbedBot(
            accountId = accountId,
            username = username,
            displayName = username,
            url = if (platform == MessagingPlatform.SLACK) "https://slack.com/app_redirect?app=A$accountId" else "https://t.me/$username",
            canManageBots = canManageBots,
        ).also { known[token] = it }

    /** The adapter of the running bot [accountId], or null when it does not run. */
    fun adapter(accountId: String): FakeMessagingAdapter? = running[accountId]?.adapter

    /** Hands the running bot [accountId] what it received, as its poller would, on the caller's thread. */
    fun receive(
        accountId: String,
        inbound: MessagingInbound,
    ) {
        val bot = running.getValue(accountId)
        bot.sink.handle(bot.adapter, inbound)
    }

    fun reset() {
        known.clear()
        running.clear()
    }

    override fun probe(
        token: String,
        appToken: String?,
    ): ProbedBot {
        if (platform == MessagingPlatform.SLACK && appToken == null) throw BadRequestException("A Slack bot also needs its app-level token")
        return known[token] ?: throw BadRequestException("$platform rejected this token")
    }

    override fun start(
        bot: MessagingBot,
        sink: MessagingSink,
        onStatus: (MessagingBotStatus) -> Unit,
    ): MessagingConnection {
        val adapter = FakeMessagingAdapter(bot.ref())
        running[bot.accountId] = Running(adapter, sink)
        onStatus(MessagingBotStatus.RUNNING)
        return object : MessagingConnection {
            override val adapter = adapter

            override fun stop() {
                running.remove(bot.accountId)
            }
        }
    }

    private data class Running(
        val adapter: FakeMessagingAdapter,
        val sink: MessagingSink,
    )
}
