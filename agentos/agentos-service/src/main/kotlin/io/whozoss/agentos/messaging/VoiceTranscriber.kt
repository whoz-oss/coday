package io.whozoss.agentos.messaging

import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Turns the audio of a voice message into the words spoken in it. */
interface VoiceTranscriber {
    /** False when this server is not set up to transcribe: the bots then say so to a voice message. */
    val available: Boolean

    /** Audio longer than this is not transcribed. */
    val maxDurationSeconds: Int

    /** Audio larger than this is not downloaded. */
    val maxBytes: Long

    /**
     * The words spoken in [audio], a file in any format ffmpeg reads; empty when none could be made out.
     *
     * @throws TranscriptionException when the audio cannot be decoded, or the transcription fails or takes too long
     */
    fun transcribe(audio: ByteArray): String
}

class TranscriptionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Transcribes on this server with whisper.cpp (`whisper-cli`), after ffmpeg has decoded the audio into the 16 kHz
 * mono WAV whisper reads: Telegram's voice messages are Ogg Opus, Slack's clips WebM or MP4. No audio leaves the
 * server. See [MessagingProperties.Transcription].
 *
 * The audio comes from a chat, so ffmpeg reads it as untrusted input: local files only (no network protocol)
 * and audio containers only, so that a playlist or a concat list posing as audio cannot make it read anything
 * else. The programs run without a shell, with fixed arguments, in a temporary directory removed afterwards.
 * At most `concurrency` transcriptions run at once, each within `timeout-seconds` once it runs; one waits as long
 * for its turn.
 */
@Component
class WhisperCppTranscriber(
    properties: MessagingProperties,
) : VoiceTranscriber {
    private val settings = properties.transcription
    private val permits = Semaphore(settings.concurrency.coerceAtLeast(1), true)

    override val available: Boolean get() = settings.model.isNotBlank()

    override val maxDurationSeconds: Int get() = settings.maxDurationSeconds

    override val maxBytes: Long get() = settings.maxBytes

    override fun transcribe(audio: ByteArray): String {
        if (!available) throw TranscriptionException("No transcription model is set (agentos.messaging.transcription.model)")
        if (!permits.tryAcquire(settings.timeoutSeconds, TimeUnit.SECONDS)) {
            throw TranscriptionException("No transcription slot freed up within ${settings.timeoutSeconds} s")
        }
        try {
            // From the slot on: the time waited for it is not taken from the transcription's own.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(settings.timeoutSeconds)
            val dir = Files.createTempDirectory("agentos-voice-")
            try {
                val input = dir.resolve("voice").also { Files.write(it, audio) }
                val wav = dir.resolve("voice.wav")
                run("ffmpeg", decode(input, wav), dir, deadline)
                return words(run("whisper", whisper(wav), dir, deadline))
            } finally {
                dir.toFile().deleteRecursively()
            }
        } finally {
            permits.release()
        }
    }

    private fun decode(
        input: Path,
        wav: Path,
    ): List<String> =
        listOf(
            settings.ffmpegCommand,
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-protocol_whitelist",
            "file",
            "-format_whitelist",
            AUDIO_FORMATS,
            "-i",
            input.toString(),
            "-t",
            settings.maxDurationSeconds.toString(),
            "-vn",
            "-ar",
            "16000",
            "-ac",
            "1",
            "-c:a",
            "pcm_s16le",
            "-y",
            wav.toString(),
        )

    private fun whisper(wav: Path): List<String> =
        listOf(
            settings.whisperCommand,
            "-m",
            settings.model,
            "-f",
            wav.toString(),
            "-l",
            settings.language.ifBlank { "auto" },
            "--no-timestamps",
            "--no-prints",
        )

    /** Runs [command] in [dir] until [deadline] and returns what it printed. */
    private fun run(
        name: String,
        command: List<String>,
        dir: Path,
        deadline: Long,
    ): String {
        val out = dir.resolve("$name.out").toFile()
        val err = dir.resolve("$name.err").toFile()
        val process =
            try {
                ProcessBuilder(command).directory(dir.toFile()).redirectOutput(out).redirectError(err).start()
            } catch (e: IOException) {
                throw TranscriptionException("$name could not be started (${command.first()}): ${e.message}", e)
            }
        process.outputStream.close()
        val left = deadline - System.nanoTime()
        if (left <= 0 || !process.waitFor(left, TimeUnit.NANOSECONDS)) {
            process.destroyForcibly()
            throw TranscriptionException("$name did not finish within ${settings.timeoutSeconds} s")
        }
        if (process.exitValue() != 0) {
            throw TranscriptionException("$name failed (exit ${process.exitValue()}): ${err.readText().trim().takeLast(MAX_ERROR_LENGTH)}")
        }
        return out.readText()
    }

    companion object {
        /** The demuxers of the audio a chat sends: Ogg (Telegram), WebM and MP4 (Slack, audio files), MP3, WAV... */
        const val AUDIO_FORMATS = "ogg,matroska,webm,mov,mp4,m4a,3gp,mp3,wav,flac,aac"
        private const val MAX_ERROR_LENGTH = 300

        /** Square brackets are whisper's marks, such as `[BLANK_AUDIO]` or `[Music]`, never spoken words. */
        private val MARKS = Regex("""\[[^\]]*]""")

        /** What whisper printed, on one line, without its marks. */
        fun words(printed: String): String = printed.replace(MARKS, " ").replace(Regex("\\s+"), " ").trim()
    }
}
