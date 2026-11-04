package io.whozoss.agentos.messaging

import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.sdk.api.messaging.MessagingBotStatus
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import org.springframework.stereotype.Component

/**
 * One messaging platform, as [MessagingBotService] and [MessagingBotSupervisor] use it: it checks a bot token
 * and runs bots. One implementation per platform, such as
 * [io.whozoss.agentos.messaging.telegram.TelegramDriver].
 */
interface MessagingDriver {
    val platform: MessagingPlatform

    /**
     * Asks the platform which bot [token] belongs to.
     *
     * @throws io.whozoss.agentos.exception.BadRequestException when the platform refuses the token
     * @throws MessagingDeliveryException when the platform cannot be reached
     */
    fun probe(token: String): ProbedBot

    /** The address that opens the bot [username] in the messaging application. */
    fun botUrl(username: String): String

    /**
     * Starts receiving what [bot] is sent and hands it to [sink], one chat at a time; returns at once. [onStatus]
     * hears whether the bot receives messages, and why not.
     */
    fun start(
        bot: MessagingBot,
        sink: MessagingSink,
        onStatus: (MessagingBotStatus) -> Unit,
    ): MessagingConnection
}

/** A bot as its platform describes it. */
data class ProbedBot(
    val accountId: String,
    val username: String,
    val displayName: String,
    /** True when the bot may create and manage other bots (Telegram's Bot Management Mode). */
    val canManageBots: Boolean,
)

/** A running bot: what to send through, and how to stop it. */
interface MessagingConnection {
    val adapter: MessagingAdapter

    /** Stops receiving. Returns at once; what is being handled finishes on its own. */
    fun stop()
}

/** Where a running bot hands what it receives: the gateway for the bot of an agent, [MessagingManagedBots] for a manager. */
fun interface MessagingSink {
    fun handle(
        adapter: MessagingAdapter,
        inbound: MessagingInbound,
    )
}

/** The [MessagingDriver] of each platform. */
@Component
class MessagingDrivers(
    drivers: List<MessagingDriver>,
) {
    private val byPlatform: Map<MessagingPlatform, MessagingDriver> = drivers.associateBy { it.platform }

    /** @throws ConflictException when no driver handles [platform] */
    fun get(platform: MessagingPlatform): MessagingDriver =
        byPlatform[platform] ?: throw ConflictException("$platform is not supported by this instance")
}
