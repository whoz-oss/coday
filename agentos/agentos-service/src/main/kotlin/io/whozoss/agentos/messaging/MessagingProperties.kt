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
 * - `transcription.*`: how voice messages are turned into text on this server ([Transcription]).
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
     * Voice messages are transcribed on this server, by whisper.cpp, after ffmpeg has decoded them: no audio
     * leaves it. Both programs must be installed where AgentOS runs. They are set here, not from the UI, because
     * they are programs the server runs.
     *
     * - `model` (`AGENTOS_MESSAGING_TRANSCRIPTION_MODEL`, empty): path of the whisper.cpp model, a `ggml-*.bin`
     *   file. Empty: voice messages are not transcribed, and the bots say so.
     * - `whisper-command` (`AGENTOS_MESSAGING_TRANSCRIPTION_WHISPER_COMMAND`, `whisper-cli`) and `ffmpeg-command`
     *   (`AGENTOS_MESSAGING_TRANSCRIPTION_FFMPEG_COMMAND`, `ffmpeg`): the programs, by path or on the `PATH`.
     * - `language` (`AGENTOS_MESSAGING_TRANSCRIPTION_LANGUAGE`, `auto`): the spoken language, `auto` to detect it.
     * - `max-duration-seconds` (`AGENTOS_MESSAGING_TRANSCRIPTION_MAX_DURATION_SECONDS`, 300) and `max-bytes`
     *   (`AGENTOS_MESSAGING_TRANSCRIPTION_MAX_BYTES`, 20 MB, Telegram's own limit for a bot): longer or larger
     *   audio is refused.
     * - `timeout-seconds` (`AGENTOS_MESSAGING_TRANSCRIPTION_TIMEOUT_SECONDS`, 120): beyond, a transcription is
     *   stopped.
     * - `concurrency` (`AGENTOS_MESSAGING_TRANSCRIPTION_CONCURRENCY`, 1): transcriptions at once on the server,
     *   whisper using every core it is given.
     */
    data class Transcription(
        val model: String = "",
        val whisperCommand: String = "whisper-cli",
        val ffmpegCommand: String = "ffmpeg",
        val language: String = "auto",
        val maxDurationSeconds: Int = 300,
        val maxBytes: Long = 20L * 1024 * 1024,
        val timeoutSeconds: Long = 120,
        val concurrency: Int = 1,
    )
}
