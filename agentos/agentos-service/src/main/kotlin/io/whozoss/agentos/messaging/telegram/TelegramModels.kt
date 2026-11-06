package io.whozoss.agentos.messaging.telegram

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

/*
 * The few objects of the Telegram Bot API (https://core.telegram.org/bots/api) AgentOS reads. Unknown fields
 * are ignored: the API adds some regularly.
 */

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUpdate(
    @JsonProperty("update_id") val updateId: Long,
    @JsonProperty("message") val message: TelegramMessage? = null,
    @JsonProperty("callback_query") val callbackQuery: TelegramCallbackQuery? = null,
    /** Only for a bot manager: a bot it manages was created, or its token or owner changed. */
    @JsonProperty("managed_bot") val managedBot: TelegramManagedBotUpdated? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramMessage(
    @JsonProperty("message_id") val messageId: Long,
    @JsonProperty("from") val from: TelegramUser? = null,
    @JsonProperty("chat") val chat: TelegramChat,
    @JsonProperty("text") val text: String? = null,
    /** A voice message, recorded in Telegram. */
    @JsonProperty("voice") val voice: TelegramAudioFile? = null,
    /** An audio file, sent as music. */
    @JsonProperty("audio") val audio: TelegramAudioFile? = null,
    // What the bot does not read: only whether the message holds some matters.
    @JsonProperty("photo") val photo: JsonNode? = null,
    @JsonProperty("document") val document: JsonNode? = null,
    @JsonProperty("video") val video: JsonNode? = null,
    @JsonProperty("video_note") val videoNote: JsonNode? = null,
    @JsonProperty("animation") val animation: JsonNode? = null,
    @JsonProperty("sticker") val sticker: JsonNode? = null,
    @JsonProperty("location") val location: JsonNode? = null,
    @JsonProperty("contact") val contact: JsonNode? = null,
    @JsonProperty("poll") val poll: JsonNode? = null,
) {
    /** True when the message holds content the bot does not read, such as a photo or a file. */
    val unreadable: Boolean
        get() = listOf(photo, document, video, videoNote, animation, sticker, location, contact, poll).any { it != null }
}

/** A voice message or an audio file: [fileId] downloads it ([TelegramBotClient.getFile]). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramAudioFile(
    @JsonProperty("file_id") val fileId: String,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("mime_type") val mimeType: String? = null,
    @JsonProperty("file_size") val fileSize: Long? = null,
)

/** A file ready to download from [filePath], as `getFile` describes it. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramFile(
    @JsonProperty("file_id") val fileId: String,
    @JsonProperty("file_size") val fileSize: Long? = null,
    @JsonProperty("file_path") val filePath: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUser(
    @JsonProperty("id") val id: Long,
    @JsonProperty("is_bot") val bot: Boolean = false,
    @JsonProperty("first_name") val firstName: String = "",
    @JsonProperty("last_name") val lastName: String? = null,
    @JsonProperty("username") val username: String? = null,
    /** Returned by `getMe` only: the bot may create and manage other bots (Bot Management Mode). */
    @JsonProperty("can_manage_bots") val canManageBots: Boolean = false,
) {
    /** The name Telegram shows for the user: first and last name, else the username, else the id. */
    val displayName: String
        get() =
            listOfNotNull(firstName.takeIf { it.isNotBlank() }, lastName?.takeIf { it.isNotBlank() })
                .joinToString(" ")
                .ifBlank { username ?: id.toString() }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramChat(
    @JsonProperty("id") val id: Long,
    /** `private`, `group`, `supergroup` or `channel`. */
    @JsonProperty("type") val type: String,
) {
    val private: Boolean get() = type == "private"
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramCallbackQuery(
    @JsonProperty("id") val id: String,
    @JsonProperty("from") val from: TelegramUser,
    @JsonProperty("message") val message: TelegramMessage? = null,
    @JsonProperty("data") val data: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramManagedBotUpdated(
    /** The user who created the bot, its owner. */
    @JsonProperty("user") val user: TelegramUser,
    @JsonProperty("bot") val bot: TelegramUser,
)
