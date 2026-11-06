package io.whozoss.agentos.messaging

import java.util.UUID

/** A bot was saved or removed: published by [MessagingBotService], applied by [MessagingBotSupervisor]. */
sealed interface MessagingBotChanged {
    data class Saved(
        val bot: MessagingBot,
    ) : MessagingBotChanged

    data class Removed(
        val botId: UUID,
    ) : MessagingBotChanged
}
