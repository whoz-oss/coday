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
 * - `slack.api-base-url` (`AGENTOS_MESSAGING_SLACK_API_BASE_URL`, `https://slack.com/api`): the Web API.
 * - `transcription.*`: the limits of the voice messages the bots take in ([Transcription]). They are transcribed with
 *   the platform's `WHISPER` AI model, as the voice button of the UI does.
 *
 * See `agentos/docs/messaging.md`.
 */
@ConfigurationProperties(prefix = "agentos.messaging")
data class MessagingProperties(
    val publicUrl: String = "",
    val linkTokenTtlMinutes: Long = 10,
    val telegram: Telegram = Telegram(),
    val slack: Slack = Slack(),
    val transcription: Transcription = Transcription(),
) {
    data class Telegram(
        val apiBaseUrl: String = "https://api.telegram.org",
        val pollTimeoutSeconds: Int = 25,
    )

    data class Slack(
        val apiBaseUrl: String = "https://slack.com/api",
    )

    /**
     * What the bots accept as a voice message. Whether one is transcribed at all depends on the platform's `WHISPER`
     * AI model, not on a setting here.
     *
     * - `max-duration-seconds` (`AGENTOS_MESSAGING_TRANSCRIPTION_MAX_DURATION_SECONDS`, 300) and `max-bytes`
     *   (`AGENTOS_MESSAGING_TRANSCRIPTION_MAX_BYTES`, 20 MB, Telegram's own limit for a bot): longer or larger
     *   audio is refused.
     */
    data class Transcription(
        val maxDurationSeconds: Int = 300,
        val maxBytes: Long = 20L * 1024 * 1024,
    )
}
