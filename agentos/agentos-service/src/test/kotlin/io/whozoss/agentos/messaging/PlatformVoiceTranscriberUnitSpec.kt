package io.whozoss.agentos.messaging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.transcription.TranscriptionService
import io.whozoss.agentos.transcription.TranscriptionUnavailableException

/** [PlatformVoiceTranscriber] over a mocked [TranscriptionService]: what it asks of it, and how it reports its failures. */
class PlatformVoiceTranscriberUnitSpec :
    StringSpec({

        val ogg = "OggS".toByteArray() + byteArrayOf(0, 2)

        fun transcriber(
            service: TranscriptionService,
            maxDurationSeconds: Int = 300,
        ) = PlatformVoiceTranscriber(
            service,
            MessagingProperties(transcription = MessagingProperties.Transcription(maxDurationSeconds = maxDurationSeconds)),
        )

        "it is available when the platform has a WHISPER model, and says so for each call" {
            val service = mockk<TranscriptionService>()
            every { service.isAvailable() } returns false andThen true

            val transcriber = transcriber(service)

            transcriber.available shouldBe false
            transcriber.available shouldBe true
        }

        "the limits are the ones of the messaging properties" {
            val transcriber = transcriber(mockk(), maxDurationSeconds = 42)

            transcriber.maxDurationSeconds shouldBe 42
            transcriber.maxBytes shouldBe 20L * 1024 * 1024
        }

        "the audio goes to the platform model with its declared type and the primary subtag of the language" {
            val service = mockk<TranscriptionService>()
            every { service.transcribe(any(), any(), any()) } returns "bonjour"

            transcriber(service).transcribe(ogg, "audio/ogg", "fr-FR") shouldBe "bonjour"

            verify(exactly = 1) { service.transcribe(ogg, "audio/ogg", "fr") }
        }

        "a language that is no two-letter code is left for the model to detect" {
            val service = mockk<TranscriptionService>()
            every { service.transcribe(any(), any(), any()) } returns "hello"
            val transcriber = transcriber(service)

            transcriber.transcribe(ogg, "audio/ogg", null)
            transcriber.transcribe(ogg, "audio/ogg", "")
            transcriber.transcribe(ogg, "audio/ogg", "x1")

            verify(exactly = 3) { service.transcribe(any(), any(), null) }
        }

        "a type that is not an audio type is replaced by the one the audio shows" {
            PlatformVoiceTranscriber.resolveMimeType("audio/ogg; codecs=opus", ogg) shouldBe "audio/ogg"
            PlatformVoiceTranscriber.resolveMimeType("video/mp4", ogg) shouldBe "audio/mp4"
            PlatformVoiceTranscriber.resolveMimeType("video/webm", ogg) shouldBe "audio/webm"
            PlatformVoiceTranscriber.resolveMimeType("application/octet-stream", ogg) shouldBe "audio/ogg"
            PlatformVoiceTranscriber.resolveMimeType(null, "RIFF....WAVE".toByteArray()) shouldBe "audio/wav"
            PlatformVoiceTranscriber.resolveMimeType(null, "ID3".toByteArray() + byteArrayOf(4)) shouldBe "audio/mpeg"
            PlatformVoiceTranscriber.resolveMimeType(null, byteArrayOf(0, 0, 0, 24) + "ftypmp42".toByteArray()) shouldBe "audio/mp4"
            PlatformVoiceTranscriber.resolveMimeType(null, byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())) shouldBe "audio/webm"
        }

        "an audio nobody can identify is taken for Ogg, which is what Telegram sends" {
            PlatformVoiceTranscriber.resolveMimeType(null, byteArrayOf(1, 2, 3)) shouldBe "audio/ogg"
        }

        "no WHISPER model, or a provider that fails, is a transcription failure" {
            val service = mockk<TranscriptionService>()
            val transcriber = transcriber(service)

            every { service.transcribe(any(), any(), any()) } throws TranscriptionUnavailableException("no model")
            shouldThrow<TranscriptionException> { transcriber.transcribe(ogg, "audio/ogg", null) }

            every { service.transcribe(any(), any(), any()) } throws RuntimeException("provider down: key sk-secret")
            val failure = shouldThrow<TranscriptionException> { transcriber.transcribe(ogg, "audio/ogg", null) }
            // The provider's own message can carry request details: it never reaches the caller.
            failure.message shouldBe "The transcription provider failed"
        }
    })
