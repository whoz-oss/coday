package io.whozoss.agentos.messaging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * [WhisperCppTranscriber] with scripts standing for ffmpeg and whisper-cli: the arguments they get, what is
 * read of their output, and their failures. The real programs are not needed.
 */
class VoiceTranscriberUnitSpec :
    StringSpec({

        val dir = Files.createTempDirectory("voice-transcriber-spec").toFile()
        afterSpec { dir.deleteRecursively() }

        /** A script named [name] that runs [body], with its arguments appended to [name].args. */
        fun script(
            name: String,
            body: String,
        ): String =
            File(dir, name)
                .apply {
                    writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\" > '${dir.absolutePath}/$name.args'\n$body\n")
                    setExecutable(true)
                }.absolutePath

        /** Copies the input file to the output one, the last argument, as a decoding would. */
        val ffmpeg = script("ffmpeg", "for last; do :; done\ncp \"\$(sed -n '/^-i\$/{n;p;}' '${dir.absolutePath}/ffmpeg.args')\" \"\$last\"")

        /** Prints what the WAV holds, as whisper-cli prints what it heard. */
        val whisper = script("whisper", "cat \"\$(sed -n '/^-f\$/{n;p;}' '${dir.absolutePath}/whisper.args')\"")

        fun transcriber(
            ffmpegCommand: String = ffmpeg,
            whisperCommand: String = whisper,
            model: String = "/models/ggml-base.bin",
            timeoutSeconds: Long = 10,
        ) = WhisperCppTranscriber(
            MessagingProperties(
                transcription =
                    MessagingProperties.Transcription(
                        model = model,
                        ffmpegCommand = ffmpegCommand,
                        whisperCommand = whisperCommand,
                        timeoutSeconds = timeoutSeconds,
                        maxDurationSeconds = 300,
                    ),
            ),
        )

        fun argsOf(name: String): List<String> = File(dir, "$name.args").readLines()

        "the audio is decoded by ffmpeg, as untrusted input, then transcribed by whisper with the model" {
            transcriber().transcribe(" [BLANK_AUDIO]\n Add a GitHub integration\n to Assistant. [Music]\n".toByteArray()) shouldBe
                "Add a GitHub integration to Assistant."

            argsOf("ffmpeg") shouldContainInOrder listOf("-protocol_whitelist", "file", "-format_whitelist", WhisperCppTranscriber.AUDIO_FORMATS, "-i")
            argsOf("ffmpeg") shouldContainInOrder listOf("-t", "300", "-vn", "-ar", "16000", "-ac", "1")
            argsOf("whisper") shouldContainInOrder listOf("-m", "/models/ggml-base.bin", "-f")
            argsOf("whisper") shouldContainInOrder listOf("-l", "auto", "--no-timestamps", "--no-prints")
        }

        "the temporary files are removed afterwards" {
            transcriber().transcribe("hello".toByteArray())

            val input = argsOf("ffmpeg").let { it[it.indexOf("-i") + 1] }
            File(input).parentFile.exists() shouldBe false
        }

        "a program that fails, cannot be found or takes too long is a transcription failure" {
            val failing = script("failing-ffmpeg", "echo 'Invalid data found when processing input' >&2\nexit 1")
            val slow = script("slow-whisper", "sleep 5")

            shouldThrow<TranscriptionException> { transcriber(ffmpegCommand = failing).transcribe(byteArrayOf(1)) }.message shouldContain
                "Invalid data found"
            shouldThrow<TranscriptionException> { transcriber(whisperCommand = "/no/such/whisper-cli").transcribe(byteArrayOf(1)) }.message shouldContain
                "could not be started"
            shouldThrow<TranscriptionException> { transcriber(whisperCommand = slow, timeoutSeconds = 1).transcribe(byteArrayOf(1)) }.message shouldContain
                "did not finish within 1 s"
        }

        "without a model, nothing is transcribed" {
            val off = transcriber(model = "")

            off.available shouldBe false
            shouldThrow<TranscriptionException> { off.transcribe(byteArrayOf(1)) }
        }
    })
