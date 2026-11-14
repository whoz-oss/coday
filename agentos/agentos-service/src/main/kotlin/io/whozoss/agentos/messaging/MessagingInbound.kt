package io.whozoss.agentos.messaging

/** What a bot received, in the form [MessagingGateway] and [MessagingManagedBots] handle whatever the platform. */
sealed interface MessagingInbound {
    val chatId: String
    val sender: MessagingSender

    /** A text message. [messageId] grows with each message of a chat, which lets a repeat be recognized. */
    data class Text(
        override val chatId: String,
        override val sender: MessagingSender,
        val messageId: Long,
        val text: String,
        /** True in the chat between the bot and the sender alone; false in a group. */
        val direct: Boolean,
    ) : MessagingInbound

    /**
     * A voice message, or an audio file, that the gateway transcribes before taking it in as a text. [messageId]
     * is ordered as a [Text]'s.
     */
    data class Voice(
        override val chatId: String,
        override val sender: MessagingSender,
        val messageId: Long,
        val audio: MessagingAudio,
        val direct: Boolean,
    ) : MessagingInbound

    /** A message the bot cannot read: a photo, a file, a sticker... The gateway says so instead of keeping silent. */
    data class Unsupported(
        override val chatId: String,
        override val sender: MessagingSender,
        val messageId: Long,
        val direct: Boolean,
    ) : MessagingInbound

    /** A click on a button of a message the bot sent. [action] is null when the button encodes none. */
    data class Click(
        override val chatId: String,
        override val sender: MessagingSender,
        val clickId: String,
        val messageId: String,
        val action: MessagingAction?,
    ) : MessagingInbound

    /**
     * A bot manager learns that [sender] created [bot], a bot it manages, or that the token or owner of one of its
     * bots changed. [chatId] is the manager's direct chat with [sender].
     */
    data class BotManaged(
        override val chatId: String,
        override val sender: MessagingSender,
        val bot: ManagedBotInfo,
    ) : MessagingInbound
}

/**
 * The audio of a [MessagingInbound.Voice], as the platform describes it: [ref] is what its adapter downloads it
 * with ([MessagingAdapter.downloadAudio]), a file id or a URL of the platform. [durationSeconds] and [sizeBytes]
 * are null when the platform does not say.
 */
data class MessagingAudio(
    val ref: String,
    val mimeType: String?,
    val durationSeconds: Int?,
    val sizeBytes: Long?,
)

/** A bot created by a bot manager, as the manager is told about it. */
data class ManagedBotInfo(
    val accountId: String,
    val username: String,
    val displayName: String,
)

data class MessagingSender(
    val externalUserId: String,
    val displayName: String,
    val bot: Boolean,
)
