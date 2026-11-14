package io.whozoss.agentos.messaging

import io.whozoss.agentos.transcription.AudioMimeTypes
import io.whozoss.agentos.transcription.TranscriptionService
import io.whozoss.agentos.transcription.TranscriptionUnavailableException
import mu.KLogging
import org.springframework.stereotype.Component

/** Turns the audio of a voice message into the words spoken in it. */
interface VoiceTranscriber {
    /** False when this server is not set up to transcribe: the bots then say so to a voice message. */
    val available: Boolean

    /** Audio longer than this is not transcribed. */
    val maxDurationSeconds: Int

    /** Audio larger than this is not downloaded. */
    val maxBytes: Long

    /**
     * The words spoken in [audio]; empty when none could be made out.
     *
     * @param mimeType the type the platform declared for the audio, when it declared one
     * @param language the user's preferred language (a BCP 47 tag such as `fr` or `en-US`), null to detect it
     * @throws TranscriptionException when the transcription fails
     */
    fun transcribe(
        audio: ByteArray,
        mimeType: String?,
        language: String?,
    ): String
}

class TranscriptionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Transcribes with the platform's `WHISPER` model, through [TranscriptionService]: the very capability the voice
 * button of the UI uses (`VoiceMessageService`), so there is nothing more to install or configure for the bots. A
 * voice message can be transcribed as soon as a platform-level AI model carries the alias `WHISPER`, which the
 * first-run setup creates for OpenAI and Requesty.
 *
 * The audio is sent to that model's provider: the declared type of the audio (or, failing that, its first bytes)
 * gives the upload the file extension the transcription API relies on to detect the format. The limits of
 * [MessagingProperties.Transcription] only decide what the bots download and accept.
 */
@Component
class PlatformVoiceTranscriber(
    private val transcriptionService: TranscriptionService,
    properties: MessagingProperties,
) : VoiceTranscriber {
    private val settings = properties.transcription

    override val available: Boolean get() = transcriptionService.isAvailable()

    override val maxDurationSeconds: Int get() = settings.maxDurationSeconds

    override val maxBytes: Long get() = settings.maxBytes

    override fun transcribe(
        audio: ByteArray,
        mimeType: String?,
        language: String?,
    ): String =
        try {
            transcriptionService.transcribe(audio, resolveMimeType(mimeType, audio), whisperLanguage(language))
        } catch (e: TranscriptionUnavailableException) {
            throw TranscriptionException("No platform-level AI model with the alias WHISPER is configured", e)
        } catch (e: Exception) {
            // The provider's message can carry request details: it is logged here, and the caller gets a generic one.
            logger.warn(e) { "[Messaging] the transcription provider failed" }
            throw TranscriptionException("The transcription provider failed", e)
        }

    companion object : KLogging() {
        /** Telegram's voice messages are Ogg Opus, and say so; this is also what an audio nobody can identify is taken for. */
        private const val FALLBACK_TYPE = "audio/ogg"

        /**
         * The audio type to upload [audio] as: the declared one when it is a known audio type (Slack declares its
         * clips `video/mp4` or `video/webm` at times: they are read as the audio they hold), else the one its first
         * bytes show, else Ogg.
         */
        fun resolveMimeType(
            declared: String?,
            audio: ByteArray,
        ): String =
            AudioMimeTypes
                .baseType(declared)
                ?.replaceFirst("video/", "audio/")
                ?.takeIf { AudioMimeTypes.extensionFor(it) != null }
                ?: sniff(audio)
                ?: FALLBACK_TYPE

        /**
         * The transcription API takes an ISO-639-1 code, the user's preference is a BCP 47 tag: the primary subtag
         * when it is two letters, otherwise null, which lets the model detect the language.
         */
        fun whisperLanguage(preferredLanguage: String?): String? =
            preferredLanguage
                ?.substringBefore('-')
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.length == 2 && it.all(Char::isLetter) }

        private fun sniff(audio: ByteArray): String? =
            when {
                audio.startsWith("OggS") -> "audio/ogg"
                audio.startsWith("RIFF") -> "audio/wav"
                audio.startsWith("fLaC") -> "audio/flac"
                audio.startsWith("ID3") -> "audio/mpeg"
                audio.size > 1 && audio[0] == 0xFF.toByte() && (audio[1].toInt() and 0xE0) == 0xE0 -> "audio/mpeg"
                audio.size > 3 && audio[0] == 0x1A.toByte() && audio[1] == 0x45.toByte() && audio[2] == 0xDF.toByte() && audio[3] == 0xA3.toByte() -> "audio/webm"
                audio.size > 7 && String(audio, 4, 4, Charsets.US_ASCII) == "ftyp" -> "audio/mp4"
                else -> null
            }

        private fun ByteArray.startsWith(prefix: String): Boolean =
            size >= prefix.length && prefix.indices.all { this[it] == prefix[it].code.toByte() }
    }
}
