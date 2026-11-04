package io.whozoss.agentos.messaging

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bound from `agentos.messaging`. Bots are not configured here: they are data, attached to agents from the UI
 * or the API ([MessagingBot]).
 *
 * - `public-url` (`AGENTOS_PUBLIC_URL`, empty): base URL of the AgentOS UI, for the links to a case or to the
 *   profile sent in a chat. Empty sends no link.
 * - `link-token-ttl-minutes` (`AGENTOS_MESSAGING_LINK_TOKEN_TTL_MINUTES`, 10): how long the link a user opens
 *   to connect a chat account, or to create a bot, stays valid.
 * - `telegram.api-base-url` (`AGENTOS_MESSAGING_TELEGRAM_API_BASE_URL`, `https://api.telegram.org`): the Bot API.
 * - `telegram.poll-timeout-seconds` (`AGENTOS_MESSAGING_TELEGRAM_POLL_TIMEOUT_SECONDS`, 25): long-polling timeout.
 *
 * See `agentos/docs/messaging.md`.
 */
@ConfigurationProperties(prefix = "agentos.messaging")
data class MessagingProperties(
    val publicUrl: String = "",
    val linkTokenTtlMinutes: Long = 10,
    val telegram: Telegram = Telegram(),
) {
    data class Telegram(
        val apiBaseUrl: String = "https://api.telegram.org",
        val pollTimeoutSeconds: Int = 25,
    )
}
