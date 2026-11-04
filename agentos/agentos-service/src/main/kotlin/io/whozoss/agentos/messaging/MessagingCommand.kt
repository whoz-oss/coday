package io.whozoss.agentos.messaging

/**
 * A chat command: a message whose first word starts with `/`.
 *
 * Only the commands named here are the gateway's. Any other `/word` is posted to the case like a normal
 * message, so that the user's prompt commands keep working from a chat.
 */
data class MessagingCommand(
    /** The command name, lowercase, without the slash nor the `@bot` suffix Telegram adds in groups. */
    val name: String,
    /** What follows the command, trimmed. Null when nothing does. */
    val argument: String?,
) {
    companion object {
        const val START = "start"
        const val HELP = "help"
        const val NEW = "new"
        const val CASE = "case"
        const val CASES = "cases"

        private val GATEWAY_COMMANDS = setOf(START, HELP, NEW, CASE, CASES)
        private val COMMAND = Regex("""^/([A-Za-z0-9_]+)(?:@\S+)?(?:\s+(.*))?$""", RegexOption.DOT_MATCHES_ALL)

        /** The gateway command [text] is, or null when it is none. */
        fun parse(text: String): MessagingCommand? =
            COMMAND
                .matchEntire(text.trim())
                ?.let { match ->
                    MessagingCommand(
                        name = match.groupValues[1].lowercase(),
                        argument = match.groupValues[2].trim().takeIf { it.isNotEmpty() },
                    )
                }?.takeIf { it.name in GATEWAY_COMMANDS }
    }
}
