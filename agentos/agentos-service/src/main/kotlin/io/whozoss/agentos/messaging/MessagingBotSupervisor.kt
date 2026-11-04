package io.whozoss.agentos.messaging

import io.whozoss.agentos.sdk.api.messaging.MessagingBotStatus
import jakarta.annotation.PreDestroy
import mu.KLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the bots of the database: all of them once the application is ready, then each one as it is saved or
 * removed ([MessagingBotChanged], after the change is committed). A bot added, turned off or removed takes
 * effect at once: there is no restart and no setting outside the database.
 *
 * The bot of an agent hands what it receives to [MessagingGateway], a bot manager to [MessagingManagedBots].
 * A bot that fails to start is logged and left off; the others run.
 */
@Component
class MessagingBotSupervisor(
    private val repository: MessagingBotRepository,
    private val drivers: MessagingDrivers,
    private val registry: MessagingBotRegistry,
    private val gateway: MessagingGateway,
    private val managedBots: MessagingManagedBots,
) {
    private val connections = ConcurrentHashMap<UUID, MessagingConnection>()

    @EventListener(ApplicationReadyEvent::class)
    fun startAll() {
        repository.findAll().forEach { start(it) }
    }

    @TransactionalEventListener(fallbackExecution = true)
    fun onBotChanged(change: MessagingBotChanged) {
        when (change) {
            is MessagingBotChanged.Saved -> start(change.bot)
            is MessagingBotChanged.Removed -> stop(change.botId)
        }
    }

    @PreDestroy
    fun stopAll() {
        connections.keys.toList().forEach { stop(it) }
    }

    /** Starts [bot] afresh, stopping what ran before under its id: its token, its role or its agent may have changed. */
    @Synchronized
    private fun start(bot: MessagingBot) {
        connections.remove(bot.id)?.stop()
        when {
            !bot.enabled -> registry.put(bot.ref(), MessagingBotStatus.DISABLED)
            else -> {
                registry.put(bot.ref(), MessagingBotStatus.STARTING)
                try {
                    val connection =
                        drivers.get(bot.platform).start(bot, sinkOf(bot)) { status ->
                            registry.setStatus(bot.id, status)
                        }
                    connections[bot.id] = connection
                    registry.setAdapter(bot.id, connection.adapter)
                    logger.info { "[Messaging] ${bot.platform} bot @${bot.username} (${bot.role.name.lowercase()}) started" }
                } catch (e: Exception) {
                    logger.error(e) { "[Messaging] ${bot.platform} bot @${bot.username} could not start" }
                    registry.setStatus(bot.id, MessagingBotStatus.UNREACHABLE)
                }
            }
        }
    }

    @Synchronized
    private fun stop(botId: UUID) {
        connections.remove(botId)?.stop()
        registry.remove(botId)
    }

    private fun sinkOf(bot: MessagingBot): MessagingSink =
        when (bot.role) {
            MessagingBotRole.AGENT -> MessagingSink(gateway::handle)
            MessagingBotRole.MANAGER -> MessagingSink(managedBots::handle)
        }

    companion object : KLogging()
}
