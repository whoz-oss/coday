package io.whozoss.agentos.messaging.slack

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.messaging.MessagingActionCodec
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
     * chat (an `im` channel), or a click on a button that comes back to AgentOS. The bot's own messages, edits and
     * other subtypes, and clicks on link buttons, mean nothing. The sender is named by their id: the adapter
     * looks the name up.
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
        return when {
            event?.get("type")?.asText() != "message" || event.has("subtype") || event.has("bot_id") -> null
            user == null || ts == null || channel == null || user == botUserId -> null
            else ->
                MessagingInbound.Text(
                    chatId = channel,
                    sender = MessagingSender(externalUserId = user, displayName = user, bot = false),
                    messageId = messageId(ts),
                    text = SlackText.toPlain(event["text"]?.asText().orEmpty()),
                    direct = event["channel_type"]?.asText() == "im",
                )
        }
    }

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
