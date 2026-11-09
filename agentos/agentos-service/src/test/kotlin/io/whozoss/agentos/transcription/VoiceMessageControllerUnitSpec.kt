package io.whozoss.agentos.transcription

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.sdk.api.case.VoiceMessageResponse
import org.springframework.mock.web.MockMultipartFile
import java.util.UUID

/** The controller only declares the routes and hands the upload to [VoiceMessageService], which has its own spec. */
class VoiceMessageControllerUnitSpec :
    StringSpec({
        "capability answers what the service reports" {
            val service = mockk<VoiceMessageService> { every { isAvailable() } returns true }

            VoiceMessageController(service).capability().available shouldBe true

            every { service.isAvailable() } returns false
            VoiceMessageController(service).capability().available shouldBe false
        }

        "postVoiceMessage hands the content type, the bytes and the form fields to the service" {
            val caseId = UUID.randomUUID()
            val answerTo = UUID.randomUUID()
            val bytes = byteArrayOf(4, 5, 6)
            val expected = VoiceMessageResponse(transcript = "hello", path = "voice-messages/voice.webm")
            val service = mockk<VoiceMessageService>()
            every { service.postVoiceMessage(caseId, "audio/webm", bytes, answerTo, 1500L) } returns expected

            val audio = MockMultipartFile("audio", "rec.webm", "audio/webm", bytes)
            val response = VoiceMessageController(service).postVoiceMessage(caseId, audio, answerTo, 1500L)

            response shouldBe expected
            verify(exactly = 1) { service.postVoiceMessage(caseId, "audio/webm", bytes, answerTo, 1500L) }
        }
    })
