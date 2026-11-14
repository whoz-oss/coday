package io.whozoss.agentos.messaging

/**
 * A chat command: a message whose first word starts with `/`, or with `!` on a platform that keeps `/` for its
 * own commands (Slack); or the `link <token>` message that links an account where a link cannot carry its token.
 *
 * Only the commands named here are the gateway's. Any other `/word` is posted to the case like a normal
 * message, so that the user's prompt commands keep working from a chat.
 */
data class MessagingCommand(
    /** The command name, lowercase, without its prefix nor the `@bot` suffix Telegram adds in groups. */
    val name: String,
    /** What follows the command, trimmed. Null when nothing does. */
    val argument: String?,
) {
    /** True for a message that links an account: `/start <token>` from a Telegram link, `link <token>` elsewhere. */
    val linksAccount: Boolean get() = (name == START || name == LINK) && !argument.isNullOrBlank()

    companion object {
        const val SLASH = "/"
        const val BANG = "!"

        const val START = "start"
        const val LINK = "link"
        const val HELP = "help"
        const val NEW = "new"
        const val CASE = "case"
        const val CASES = "cases"

        private val GATEWAY_COMMANDS = setOf(START, HELP, NEW, CASE, CASES)
        private val COMMAND = Regex("""^[/!]([A-Za-z0-9_]+)(?:@\S+)?(?:\s+(.*))?$""", RegexOption.DOT_MATCHES_ALL)

        /** `link` and a token alone: what a user pastes, so the token is all one word. */
        private val LINK_MESSAGE = Regex("""^link\s+([A-Za-z0-9_-]{20,})$""", RegexOption.IGNORE_CASE)

        /** The gateway command [text] is, or null when it is none. */
        fun parse(text: String): MessagingCommand? {
            val trimmed = text.trim()
            LINK_MESSAGE.matchEntire(trimmed)?.let { return MessagingCommand(name = LINK, argument = it.groupValues[1]) }
            return COMMAND
                .matchEntire(trimmed)
                ?.let { match ->
                    MessagingCommand(
                        name = match.groupValues[1].lowercase(),
                        argument = match.groupValues[2].trim().takeIf { it.isNotEmpty() },
                    )
                }?.takeIf { it.name in GATEWAY_COMMANDS }
        }

        /** The message that links an account through [token], on a platform whose links cannot carry it. */
        fun linkMessage(token: String): String = "$LINK $token"
    }
}
