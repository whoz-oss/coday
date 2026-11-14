package io.whozoss.agentos.messaging

import io.whozoss.agentos.sdk.api.messaging.MessagingBotStatus
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The bots this instance runs, as [MessagingBotSupervisor] keeps them: their adapter and whether they receive
 * messages. Read by the gateway, the delivery and the API; written by the supervisor only.
 */
@Component
class MessagingBotRegistry {
    private val bots = ConcurrentHashMap<UUID, Entry>()

    /** The adapter of [botId], or null when the bot does not run. */
    fun adapter(botId: UUID): MessagingAdapter? = bots[botId]?.adapter

    /** The adapter of the bot [accountId] of [platform], or null when it does not run. */
    fun adapter(
        platform: MessagingPlatform,
        accountId: String,
    ): MessagingAdapter? = bots.values.firstOrNull { it.ref.platform == platform && it.ref.accountId == accountId }?.adapter

    /** The bot manager of [platform] when it receives messages: creating a bot through it works now. */
    fun manager(platform: MessagingPlatform): MessagingManagerAdapter? =
        bots.values
            .filter { it.ref.platform == platform && it.ref.role == MessagingBotRole.MANAGER }
            .firstOrNull { it.status == MessagingBotStatus.RUNNING }
            ?.adapter as? MessagingManagerAdapter

    /** Whether [botId] receives messages; null when the supervisor does not know the bot. */
    fun status(botId: UUID): MessagingBotStatus? = bots[botId]?.status

    internal fun put(
        ref: MessagingBotRef,
        status: MessagingBotStatus,
    ) {
        bots[ref.id] = Entry(ref = ref, adapter = null, status = status)
    }

    internal fun setAdapter(
        botId: UUID,
        adapter: MessagingAdapter,
    ) {
        bots.computeIfPresent(botId) { _, entry -> entry.copy(adapter = adapter) }
    }

    internal fun setStatus(
        botId: UUID,
        status: MessagingBotStatus,
    ) {
        bots.computeIfPresent(botId) { _, entry -> entry.copy(status = status) }
    }

    internal fun remove(botId: UUID) {
        bots.remove(botId)
    }

    private data class Entry(
        val ref: MessagingBotRef,
        val adapter: MessagingAdapter?,
        val status: MessagingBotStatus,
    )
}
