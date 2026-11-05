package io.whozoss.agentos.transcription

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.TranscriptionModelFactory
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.ai.audio.transcription.AudioTranscription
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse
import org.springframework.ai.audio.transcription.TranscriptionModel
import java.util.UUID

class TranscriptionServiceUnitSpec :
    StringSpec({

        val audio = byteArrayOf(1, 2, 3)

        fun provider(
            apiType: AiApiType = AiApiType.OpenAI,
            baseUrl: String? = "https://whisper.example",
            apiKey: String? = "sk-test",
            headers: Map<String, String> = emptyMap(),
        ) = AiProvider(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            name = "provider-${apiType.name}",
            apiType = apiType,
            baseUrl = baseUrl,
            apiKey = apiKey,
            headers = headers,
        )

        fun whisperModel(
            provider: AiProvider,
            alias: String? = "WHISPER",
            namespaceId: UUID? = null,
            userId: UUID? = null,
            apiModelName: String = "whisper-1",
            priority: Int = 0,
        ) = AiModel(
            metadata = EntityMetadata(),
            aiProviderId = provider.metadata.id,
            namespaceId = namespaceId,
            userId = userId,
            apiModelName = apiModelName,
            alias = alias,
            priority = priority,
        )

        /** Factory mock returning a [TranscriptionModel] that answers [text] without any network call. */
        fun stubbedFactory(text: String = "bonjour le monde"): TranscriptionModelFactory {
            val transcriptionModel = mockk<TranscriptionModel>()
            every { transcriptionModel.call(any<AudioTranscriptionPrompt>()) } returns
                AudioTranscriptionResponse(AudioTranscription(text))
            val factory = mockk<TranscriptionModelFactory>()
            every { factory.createTranscriptionModel(any(), any(), any(), any(), any()) } returns transcriptionModel
            return factory
        }

        fun buildService(
            models: List<AiModel>,
            providers: List<AiProvider>,
            factory: TranscriptionModelFactory = stubbedFactory(),
        ): TranscriptionService {
            val aiModelService = mockk<AiModelService> { every { findPlatformLevel() } returns models }
            val aiProviderService = mockk<AiProviderService>()
            providers.forEach { p -> every { aiProviderService.getById(p.metadata.id) } returns p }
            return TranscriptionService(aiModelService, aiProviderService, factory)
        }

        "uses the platform WHISPER model and returns the transcript" {
            val p = provider(headers = mapOf("X-Custom" to "v"))
            val factory = stubbedFactory("hello world")
            val service = buildService(listOf(whisperModel(p, apiModelName = "whisper-large")), listOf(p), factory)

            service.isAvailable() shouldBe true
            service.transcribe(audio, "audio/webm", null) shouldBe "hello world"

            verify(exactly = 1) {
                factory.createTranscriptionModel(
                    baseUrl = "https://whisper.example",
                    apiKey = "sk-test",
                    modelName = "whisper-large",
                    language = null,
                    headers = mapOf("X-Custom" to "v"),
                )
            }
        }

        "matches the WHISPER alias case-insensitively" {
            val p = provider()
            val service = buildService(listOf(whisperModel(p, alias = "WhIsPeR")), listOf(p))

            service.isAvailable() shouldBe true
            service.transcribe(audio, "audio/webm", null) shouldBe "bonjour le monde"
        }

        "works with a vLLM provider" {
            val p = provider(apiType = AiApiType.vLLM)
            val service = buildService(listOf(whisperModel(p)), listOf(p))

            service.isAvailable() shouldBe true
            service.transcribe(audio, "audio/webm", null) shouldBe "bonjour le monde"
        }

        "ignores a namespace-level WHISPER model" {
            val p = provider()
            val service = buildService(listOf(whisperModel(p, namespaceId = UUID.randomUUID())), listOf(p))

            service.isAvailable() shouldBe false
            shouldThrow<TranscriptionUnavailableException> { service.transcribe(audio, "audio/webm", null) }
        }

        "ignores a user-level WHISPER model" {
            val p = provider()
            val service = buildService(listOf(whisperModel(p, userId = UUID.randomUUID())), listOf(p))

            service.isAvailable() shouldBe false
            shouldThrow<TranscriptionUnavailableException> { service.transcribe(audio, "audio/webm", null) }
        }

        "uses the platform WHISPER model even when namespace and user level ones exist" {
            val platformProvider = provider(baseUrl = "https://platform.example")
            val otherProvider = provider(baseUrl = "https://other.example")
            val factory = stubbedFactory()
            val service =
                buildService(
                    models =
                        listOf(
                            whisperModel(otherProvider, namespaceId = UUID.randomUUID(), priority = 100),
                            whisperModel(otherProvider, userId = UUID.randomUUID(), priority = 100),
                            whisperModel(platformProvider),
                        ),
                    providers = listOf(platformProvider, otherProvider),
                    factory = factory,
                )

            service.transcribe(audio, "audio/webm", null)

            verify(exactly = 1) {
                factory.createTranscriptionModel("https://platform.example", any(), any(), any(), any())
            }
        }

        "ignores platform models with another alias" {
            val p = provider()
            val service = buildService(listOf(whisperModel(p, alias = "default"), whisperModel(p, alias = null)), listOf(p))

            service.isAvailable() shouldBe false
        }

        "is unavailable and throws when no model exists" {
            val service = buildService(emptyList(), emptyList())

            service.isAvailable() shouldBe false
            shouldThrow<TranscriptionUnavailableException> { service.transcribe(audio, "audio/webm", null) }
                .message shouldContain "WHISPER"
        }

        "availability is driven by the alias only: an Anthropic provider with a platform WHISPER model is available" {
            val p = provider(apiType = AiApiType.Anthropic, headers = mapOf("X-Custom" to "v"))
            val factory = stubbedFactory()
            val service = buildService(listOf(whisperModel(p)), listOf(p), factory)

            service.isAvailable() shouldBe true
            service.transcribe(audio, "audio/webm", null) shouldBe "bonjour le monde"

            verify(exactly = 1) {
                factory.createTranscriptionModel(
                    baseUrl = "https://whisper.example",
                    apiKey = "sk-test",
                    modelName = "whisper-1",
                    language = null,
                    headers = mapOf("X-Custom" to "v"),
                )
            }
        }

        "availability is driven by the alias only: a Gemini provider with a platform WHISPER model is available" {
            val p = provider(apiType = AiApiType.Gemini)
            val service = buildService(listOf(whisperModel(p)), listOf(p))

            service.isAvailable() shouldBe true
            service.transcribe(audio, "audio/webm", null) shouldBe "bonjour le monde"
        }

        "the highest priority platform WHISPER model wins" {
            val low = provider(baseUrl = "https://low.example")
            val high = provider(baseUrl = "https://high.example")
            val factory = stubbedFactory()
            val service =
                buildService(
                    models = listOf(whisperModel(low, priority = 1), whisperModel(high, priority = 5)),
                    providers = listOf(low, high),
                    factory = factory,
                )

            service.transcribe(audio, "audio/webm", null)

            verify(exactly = 1) {
                factory.createTranscriptionModel("https://high.example", any(), any(), any(), any())
            }
        }

        "passes the language through to the factory" {
            val p = provider()
            val factory = stubbedFactory()
            val service = buildService(listOf(whisperModel(p)), listOf(p), factory)

            service.transcribe(audio, "audio/ogg", "fr")

            verify(exactly = 1) {
                factory.createTranscriptionModel(any(), any(), any(), language = "fr", headers = any())
            }
        }

        "treats a blank language as auto-detect" {
            val p = provider()
            val factory = stubbedFactory()
            val service = buildService(listOf(whisperModel(p)), listOf(p), factory)

            service.transcribe(audio, "audio/webm", "  ")

            verify(exactly = 1) {
                factory.createTranscriptionModel(any(), any(), any(), language = null, headers = any())
            }
        }

        fun transcriptOf(output: String): String {
            val p = provider()
            val service = buildService(listOf(whisperModel(p)), listOf(p), stubbedFactory(output))
            return service.transcribe(audio, "audio/webm", null)
        }

        "keeps a plain text transcript" {
            transcriptOf("bonjour tout le monde") shouldBe "bonjour tout le monde"
        }

        "extracts the text field of a JSON object answer" {
            transcriptOf("""{"text":"bonjour tout le monde","language":"fr","duration":3.2}""") shouldBe "bonjour tout le monde"
        }

        "extracts the text field of a pretty-printed JSON answer surrounded by whitespace" {
            transcriptOf("\n  {\n  \"text\": \" salut \"\n}\n ") shouldBe "salut"
        }

        "keeps a JSON object without a text field as is" {
            transcriptOf("""{"result":"bonjour"}""") shouldBe """{"result":"bonjour"}"""
        }

        "keeps a JSON object whose text field is not a string as is" {
            transcriptOf("""{"text":42}""") shouldBe """{"text":42}"""
        }

        "keeps text that merely looks like JSON but does not parse" {
            transcriptOf("{pas du json") shouldBe "{pas du json"
        }

        "trims whitespace around a plain transcript" {
            transcriptOf("  \n bonjour \t\n") shouldBe "bonjour"
        }

        "a whitespace only transcript becomes blank" {
            transcriptOf(" \n ") shouldBe ""
        }
    })
