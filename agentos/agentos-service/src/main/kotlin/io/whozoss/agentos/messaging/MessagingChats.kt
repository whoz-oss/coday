package io.whozoss.agentos.messaging

/**
 * The address of a chat in the messaging code: a platform's chat id, or one thread of that chat
 * (`<chat>/<thread>`), such as a topic of a Telegram private chat in threaded mode. Each address is a chat of its
 * own, with its own conversation, case and lane; only the platform's adapter reads the thread, to send there.
 * Neither Telegram's nor Slack's chat ids hold a `/`.
 */
object MessagingChats {
    private const val THREAD_SEPARATOR = '/'

    fun address(
        chatId: String,
        threadId: String?,
    ): String = if (threadId.isNullOrEmpty()) chatId else "$chatId$THREAD_SEPARATOR$threadId"

    /** The platform's chat of [address], without its thread. */
    fun chatOf(address: String): String = address.substringBefore(THREAD_SEPARATOR)

    /** The thread of [address], or null for the chat itself. */
    fun threadOf(address: String): String? = address.substringAfter(THREAD_SEPARATOR, "").ifEmpty { null }
}
