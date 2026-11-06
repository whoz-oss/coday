package io.whozoss.agentos.messaging.slack

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.messaging.MessagingActionCodec
import io.whozoss.agentos.messaging.MessagingAudio
import io.whozoss.agentos.messaging.MessagingInbound
import io.whozoss.agentos.messaging.MessagingSender

/**
 * What arrives over a Socket Mode connection (https://docs.slack.dev/apis/events-api/using-socket-mode), read
 * into what the messaging code handles. Pure functions, so that the protocol is tested without a socket.
 */
object SlackEvents {
    /** One frame of the connection. */
    sealed interface Frame {
        /** The connection is ready. */
        data object Hello : Frame

        /** An event or an interaction, to acknowledge with [ack] at once. */
        data class Envelope(
            val envelopeId: String,
            val type: String,
            val payload: JsonNode,
        ) : Frame {
            val ack: String get() = """{"envelope_id":"$envelopeId"}"""
        }

        /** Slack is about to close the connection: open another one. */
        data class Disconnect(
            val reason: String,
        ) : Frame

        data object Other : Frame
    }

    fun read(
        objectMapper: ObjectMapper,
        text: String,
    ): Frame {
        val frame = runCatching { objectMapper.readTree(text) }.getOrNull() ?: return Frame.Other
        val envelopeId = frame["envelope_id"]?.asText()
        return when (val type = frame["type"]?.asText()) {
            "hello" -> Frame.Hello
            "disconnect" -> Frame.Disconnect(frame["reason"]?.asText().orEmpty())
            null -> Frame.Other
            else ->
                envelopeId
                    ?.let { Frame.Envelope(it, type, frame["payload"] ?: objectMapper.createObjectNode()) }
                    ?: Frame.Other
        }
    }

    /**
     * What [envelope] means to the bot [botUserId], or null when nothing: a message of a person in the bot's direct
     * chat (an `im` channel), or a click on a button that comes back to AgentOS. A message with files is a voice
     * message when one of them is audio (a clip recorded in Slack), else one the bot cannot read. The bot's own
     * messages, edits and other subtypes, and clicks on link buttons, mean nothing. The sender is named by their
     * id: the adapter looks the name up.
     */
    fun inbound(
        envelope: Frame.Envelope,
        botUserId: String,
    ): MessagingInbound? =
        when (envelope.type) {
            "events_api" -> message(envelope.payload["event"], botUserId)
            "interactive" -> click(envelope.payload)
            else -> null
        }

    private fun message(
        event: JsonNode?,
        botUserId: String,
    ): MessagingInbound? {
        val user = event?.get("user")?.asText()
        val ts = event?.get("ts")?.asText()
        val channel = event?.get("channel")?.asText()
        val subtype = event?.get("subtype")?.asText()
        val files = event?.get("files")?.takeIf { it.isArray && !it.isEmpty }
        val direct = event?.get("channel_type")?.asText() == "im"
        return when {
            event?.get("type")?.asText() != "message" || (subtype != null && subtype != FILE_SHARE) || event.has("bot_id") -> null
            user == null || ts == null || channel == null || user == botUserId -> null
            files != null -> {
                val sender = MessagingSender(externalUserId = user, displayName = user, bot = false)
                files.firstOrNull(::isAudio)?.let { file ->
                    MessagingInbound.Voice(chatId = channel, sender = sender, messageId = messageId(ts), audio = audioOf(file), direct = direct)
                } ?: MessagingInbound.Unsupported(chatId = channel, sender = sender, messageId = messageId(ts), direct = direct)
            }
            subtype != null -> null
            else ->
                MessagingInbound.Text(
                    chatId = channel,
                    sender = MessagingSender(externalUserId = user, displayName = user, bot = false),
                    messageId = messageId(ts),
                    text = SlackText.toPlain(event["text"]?.asText().orEmpty()),
                    direct = direct,
                )
        }
    }

    /** A clip recorded in Slack (`slack_audio`), or an audio file. */
    private fun isAudio(file: JsonNode): Boolean =
        file["subtype"]?.asText() == "slack_audio" || file["mimetype"]?.asText()?.startsWith("audio/") == true

    /** The file's download URL, or its id when the event carries none (`file_access: check_file_info`). */
    private fun audioOf(file: JsonNode): MessagingAudio =
        MessagingAudio(
            ref = (file["url_private_download"] ?: file["url_private"] ?: file["id"])?.asText().orEmpty(),
            mimeType = file["mimetype"]?.asText(),
            durationSeconds = file["duration_ms"]?.asLong()?.let { ((it + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt() },
            sizeBytes = file["size"]?.asLong(),
        )

    private fun click(payload: JsonNode): MessagingInbound? {
        val action = payload["actions"]?.firstOrNull()
        val user = payload["user"]?.get("id")?.asText()
        val channel = payload["channel"]?.get("id")?.asText() ?: payload["container"]?.get("channel_id")?.asText()
        val ts = payload["message"]?.get("ts")?.asText() ?: payload["container"]?.get("message_ts")?.asText()
        return when {
            payload["type"]?.asText() != "block_actions" || action == null || action.has("url") -> null
            user == null || channel == null || ts == null -> null
            else ->
                MessagingInbound.Click(
                    chatId = channel,
                    sender = MessagingSender(externalUserId = user, displayName = user, bot = false),
                    clickId = "$channel:$user",
                    messageId = ts,
                    action = MessagingActionCodec.decode(action["value"]?.asText()),
                )
        }
    }

    private const val FILE_SHARE = "file_share"
    private const val MILLIS_PER_SECOND = 1000L

    /** A message timestamp (`1700000000.123456`) as a number that grows with each message of a channel. */
    fun messageId(ts: String): Long = ts.replace(".", "").toLongOrNull() ?: 0
}

/** The text of a Slack message as a person wrote it: Slack escapes `& < >` and wraps links and mentions. */
object SlackText {
    private val LINK = Regex("""<((?:https?|mailto):[^|>]+)(?:\|([^>]+))?>""")

    fun toPlain(text: String): String =
        LINK
            .replace(text) { match ->
                val url = match.groupValues[1]
                val label = match.groupValues[2]
                if (label.isEmpty() || label == url) url else "$label ($url)"
            }.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
}
