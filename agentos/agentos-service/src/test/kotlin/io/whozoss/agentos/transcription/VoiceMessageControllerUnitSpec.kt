package io.whozoss.agentos.transcription

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.exchange.ExchangeCapabilityService
import io.whozoss.agentos.exchange.ExchangeRootResolver
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.exchange.ResolvedExchangeRoot
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.api.exchange.ExchangeScope
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class VoiceMessageControllerUnitSpec :
    StringSpec({
        class Fixture(
            preferredLanguage: String? = "fr",
            available: Boolean = true,
        ) {
            val case = Case(namespaceId = UUID.randomUUID())
            val user = User(externalId = "voice-user", email = "voice@example.com", firstname = "Ada", preferredLanguage = preferredLanguage)
            val root = ResolvedExchangeRoot(Path.of("/provider/documents"), case.id)
            val transcription =
                mockk<TranscriptionService> {
                    every { isAvailable() } returns available
                    every { transcribe(any(), any(), any()) } returns "hello there"
                }
            val cases =
                mockk<CaseService>(relaxed = true) {
                    every { findById(case.id) } returns case
                }
            val storage = mockk<ExchangeStorageService>(relaxed = true)
            val resolver =
                object : ExchangeRootResolver {
                    override fun resolve(case: Case) = root

                    override fun resolve(
                        caseId: UUID,
                        namespaceId: UUID,
                        caseCreatedAt: Instant,
                    ) = root

                    override fun <T> withFileMutation(
                        case: Case,
                        action: (ResolvedExchangeRoot) -> T,
                    ): T = action(root)
                }
            val controller =
                VoiceMessageController(
                    transcription,
                    cases,
                    resolver,
                    storage,
                    ExchangeCapabilityService(mockk<PermissionService>(relaxed = true)),
                    mockk<UserService> { every { getCurrentUser() } returns user },
                )

            fun audio(
                contentType: String? = "audio/webm",
                bytes: ByteArray = byteArrayOf(1, 2, 3),
            ) = MockMultipartFile("audio", "rec.webm", contentType, bytes)

            /** Nothing was transcribed, stored or posted. */
            fun verifyNothingHappened() {
                verify(exactly = 0) { transcription.transcribe(any(), any(), any()) }
                verify(exactly = 0) { storage.writeNew(any(), any(), any(), any()) }
                verify(exactly = 0) { cases.addMessage(any(), any(), any(), any(), any()) }
            }
        }

        // -------------------------------------------------------------------------
        // Capability
        // -------------------------------------------------------------------------

        "capability reports available when transcription is available" {
            Fixture(available = true).controller.capability().available shouldBe true
        }

        "capability reports unavailable when transcription is not" {
            Fixture(available = false).controller.capability().available shouldBe false
        }

        // -------------------------------------------------------------------------
        // Rejections: nothing transcribed, stored or posted
        // -------------------------------------------------------------------------

        "capability off is a conflict and nothing is transcribed, stored or posted" {
            val f = Fixture(available = false)

            shouldThrow<ConflictException> { f.controller.postVoiceMessage(f.case.id, f.audio(), null, null) }

            f.verifyNothingHappened()
        }

        "an unsupported MIME type is a 400" {
            val f = Fixture()

            shouldThrow<BadRequestException> { f.controller.postVoiceMessage(f.case.id, f.audio("video/mp4"), null, null) }
            shouldThrow<BadRequestException> { f.controller.postVoiceMessage(f.case.id, f.audio("application/octet-stream"), null, null) }
            shouldThrow<BadRequestException> { f.controller.postVoiceMessage(f.case.id, f.audio(null), null, null) }

            f.verifyNothingHappened()
        }

        "an empty recording is a 400" {
            val f = Fixture()

            shouldThrow<BadRequestException> { f.controller.postVoiceMessage(f.case.id, f.audio(bytes = ByteArray(0)), null, null) }

            f.verifyNothingHappened()
        }

        "a recording above 25 MB is a 413" {
            val f = Fixture()
            val tooBig = MockMultipartFile("audio", "rec.webm", "audio/webm", ByteArray(25 * 1024 * 1024 + 1))

            val e = shouldThrow<ResponseStatusException> { f.controller.postVoiceMessage(f.case.id, tooBig, null, null) }

            e.statusCode shouldBe HttpStatus.PAYLOAD_TOO_LARGE
            f.verifyNothingHappened()
        }

        "a recording of exactly 25 MB is accepted" {
            val f = Fixture()
            val limit = MockMultipartFile("audio", "rec.webm", "audio/webm", ByteArray(25 * 1024 * 1024))

            f.controller.postVoiceMessage(f.case.id, limit, null, null).transcript shouldBe "hello there"
        }

        "a transcription failure answers 502 and stores and posts nothing" {
            val f = Fixture()
            every { f.transcription.transcribe(any(), any(), any()) } throws RuntimeException("provider down: key sk-secret")

            val e = shouldThrow<ResponseStatusException> { f.controller.postVoiceMessage(f.case.id, f.audio(), null, null) }

            e.statusCode shouldBe HttpStatus.BAD_GATEWAY
            (e.reason ?: "") shouldBe "The recording could not be transcribed"
            verify(exactly = 0) { f.storage.writeNew(any(), any(), any(), any()) }
            verify(exactly = 0) { f.cases.addMessage(any(), any(), any(), any(), any()) }
        }

        "a capability lost between the check and the call is a conflict, nothing stored or posted" {
            val f = Fixture()
            every { f.transcription.transcribe(any(), any(), any()) } throws TranscriptionUnavailableException("no model")

            shouldThrow<ConflictException> { f.controller.postVoiceMessage(f.case.id, f.audio(), null, null) }

            verify(exactly = 0) { f.storage.writeNew(any(), any(), any(), any()) }
            verify(exactly = 0) { f.cases.addMessage(any(), any(), any(), any(), any()) }
        }

        "a blank transcript is a 422 and stores and posts nothing" {
            val f = Fixture()
            every { f.transcription.transcribe(any(), any(), any()) } returns "   "

            shouldThrow<UnprocessableEntityException> { f.controller.postVoiceMessage(f.case.id, f.audio(), null, null) }

            verify(exactly = 0) { f.storage.writeNew(any(), any(), any(), any()) }
            verify(exactly = 0) { f.cases.addMessage(any(), any(), any(), any(), any()) }
        }

        // -------------------------------------------------------------------------
        // Happy path
        // -------------------------------------------------------------------------

        "transcribes, stores under voice-messages/ then posts [Audio, Text], and answers transcript and path" {
            val f = Fixture(preferredLanguage = "fr")
            val bytes = byteArrayOf(9, 8, 7)
            val pathSlot = slot<String>()
            val contentSlot = slot<List<MessageContent>>()
            val actorSlot = slot<Actor>()
            every { f.storage.writeNew(f.root.path, capture(pathSlot), bytes, ExchangeScope.CASE) } returns mockk(relaxed = true)
            val answerTo = UUID.randomUUID()

            val response = f.controller.postVoiceMessage(f.case.id, f.audio("audio/webm", bytes), answerTo, 4200L)

            pathSlot.captured shouldMatch Regex("voice-messages/voice-\\d{8}-\\d{6}-[0-9a-f]{8}\\.webm")
            response.transcript shouldBe "hello there"
            response.path shouldBe pathSlot.captured

            verifyOrder {
                f.transcription.transcribe(bytes, "audio/webm", "fr")
                f.storage.writeNew(f.root.path, pathSlot.captured, bytes, ExchangeScope.CASE)
                f.cases.addMessage(f.case.id, capture(actorSlot), capture(contentSlot), answerTo, null)
            }
            contentSlot.captured shouldBe
                listOf(
                    MessageContent.Audio(path = pathSlot.captured, mimeType = "audio/webm", durationMs = 4200L),
                    MessageContent.Text("hello there"),
                )
            actorSlot.captured shouldBe Actor(id = f.user.id.toString(), displayName = "Ada", role = ActorRole.USER)
        }

        "a codecs parameter is accepted and the base type is stored on the message" {
            val f = Fixture()
            val contentSlot = slot<List<MessageContent>>()
            every { f.cases.addMessage(f.case.id, any(), capture(contentSlot), any(), any()) } returns Unit

            f.controller.postVoiceMessage(f.case.id, f.audio("audio/webm;codecs=opus"), null, null)

            (contentSlot.captured.first() as MessageContent.Audio).mimeType shouldBe "audio/webm"
            verify { f.transcription.transcribe(any(), "audio/webm", any()) }
        }

        "every accepted type is stored with its extension" {
            val expected =
                mapOf(
                    "audio/webm" to "webm",
                    "audio/ogg" to "ogg",
                    "audio/mp4" to "m4a",
                    "audio/mpeg" to "mp3",
                    "audio/mp3" to "mp3",
                    "audio/wav" to "wav",
                    "audio/x-wav" to "wav",
                    "audio/m4a" to "m4a",
                    "audio/x-m4a" to "m4a",
                )
            expected.forEach { (mime, extension) ->
                val f = Fixture()
                val pathSlot = slot<String>()
                every { f.storage.writeNew(any(), capture(pathSlot), any(), any()) } returns mockk(relaxed = true)

                f.controller.postVoiceMessage(f.case.id, f.audio(mime.uppercase()), null, null)

                pathSlot.captured shouldContain "voice-messages/voice-"
                pathSlot.captured.substringAfterLast('.') shouldBe extension
            }
        }

        "the language is the primary subtag of the user's preferred language, null when there is none" {
            val regional = Fixture(preferredLanguage = "en-US")
            regional.controller.postVoiceMessage(regional.case.id, regional.audio(), null, null)
            verify { regional.transcription.transcribe(any(), any(), "en") }

            val none = Fixture(preferredLanguage = null)
            none.controller.postVoiceMessage(none.case.id, none.audio(), null, null)
            verify { none.transcription.transcribe(any(), any(), null) }
        }

        "a missing duration and answerToEventId are passed as null" {
            val f = Fixture()
            val contentSlot = slot<List<MessageContent>>()
            every { f.cases.addMessage(f.case.id, any(), capture(contentSlot), null, null) } returns Unit

            f.controller.postVoiceMessage(f.case.id, f.audio(), null, null)

            (contentSlot.captured.first() as MessageContent.Audio).durationMs shouldBe null
        }
    })
