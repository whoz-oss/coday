package io.whozoss.agentos.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.entity.ExternalIdentifierResolver
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import java.util.UUID

/**
 * Unit tests for [PromptTranslationServiceImpl].
 *
 * `ChatClient.CallResponseSpec.content()` returns `String?`, which causes MockK
 * type-inference failures on `answers { }` / `returns`. We sidestep this by
 * constructing real [ChatClient] instances backed by a fake [ChatModel] that returns a
 * pre-canned [ChatResponse]. No MockK stubs touch the nullable `content()` method.
 */
class PromptTranslationServiceImplSpec : StringSpec() {
    private val aiModelService = mockk<AiModelService>()
    private val aiProviderService = mockk<AiProviderService>()
    private val chatClientProvider = mockk<ChatClientProvider>()
    private val externalIdentifierResolver = mockk<ExternalIdentifierResolver>()

    private val service =
        PromptTranslationServiceImpl(
            aiModelService = aiModelService,
            aiProviderService = aiProviderService,
            chatClientProvider = chatClientProvider,
            externalIdentifierResolver = externalIdentifierResolver,
        )

    private val namespaceId = UUID.randomUUID()
    private val model = mockk<AiModel>(relaxed = true)
    private val provider = mockk<AiProvider>(relaxed = true)

    init {
        beforeEach {
            every { aiModelService.findAiModel(namespaceId) } returns model
            every { model.aiProviderId } returns UUID.randomUUID()
            every { aiProviderService.getById(any()) } returns provider
        }

        // -------------------------------------------------------------------------
        // translateContent
        // -------------------------------------------------------------------------

        "translateContent returns translated list with one entry per source element" {
            every { chatClientProvider.getChatClient(model, provider) } returns chatClientReturning("Bonjour")

            val result =
                service.translateContent(
                    content = listOf("Hello"),
                    sourceLanguage = "en",
                    targetLanguage = "fr",
                    namespaceId = namespaceId,
                    namespaceExternalId = null,
                )

            result shouldBe listOf("Bonjour")
        }

        "translateContent translates each element independently" {
            val responses = mutableListOf("Bonjour", "Au revoir")
            every { chatClientProvider.getChatClient(model, provider) } returns chatClientReturningSequence(responses)

            val result =
                service.translateContent(
                    content = listOf("Hello", "Goodbye"),
                    sourceLanguage = "en",
                    targetLanguage = "fr",
                    namespaceId = namespaceId,
                    namespaceExternalId = null,
                )

            result shouldBe listOf("Bonjour", "Au revoir")
        }

        "translateContent falls back to original element when LLM returns blank" {
            every { chatClientProvider.getChatClient(model, provider) } returns chatClientReturning("")

            val result =
                service.translateContent(
                    content = listOf("Hello"),
                    sourceLanguage = "en",
                    targetLanguage = "fr",
                    namespaceId = namespaceId,
                    namespaceExternalId = null,
                )

            result shouldBe listOf("Hello")
        }

        "translateContent falls back to original element when LLM call throws" {
            every {
                chatClientProvider.getChatClient(
                    model,
                    provider,
                )
            } returns chatClientThrowing(RuntimeException("LLM unavailable"))

            val result =
                service.translateContent(
                    content = listOf("Hello"),
                    sourceLanguage = "en",
                    targetLanguage = "fr",
                    namespaceId = namespaceId,
                    namespaceExternalId = null,
                )

            result shouldBe listOf("Hello")
        }

        // -------------------------------------------------------------------------
        // translateTitle
        // -------------------------------------------------------------------------

        "translateTitle returns translated string" {
            every { chatClientProvider.getChatClient(model, provider) } returns chatClientReturning("Revoir le profil")

            val result =
                service.translateTitle(
                    title = "Review profile",
                    sourceLanguage = "en",
                    targetLanguage = "fr",
                    namespaceId = namespaceId,
                    namespaceExternalId = null,
                )

            result shouldBe "Revoir le profil"
        }

        "translateTitle falls back to original when LLM returns blank" {
            every { chatClientProvider.getChatClient(model, provider) } returns chatClientReturning("  ")

            val result =
                service.translateTitle(
                    title = "Review profile",
                    sourceLanguage = "en",
                    targetLanguage = "fr",
                    namespaceId = namespaceId,
                    namespaceExternalId = null,
                )

            result shouldBe "Review profile"
        }

        "translateTitle falls back to original when LLM call throws" {
            every {
                chatClientProvider.getChatClient(
                    model,
                    provider,
                )
            } returns chatClientThrowing(RuntimeException("timeout"))

            val result =
                service.translateTitle(
                    title = "Review profile",
                    sourceLanguage = "en",
                    targetLanguage = "fr",
                    namespaceId = namespaceId,
                    namespaceExternalId = null,
                )

            result shouldBe "Review profile"
        }

        // -------------------------------------------------------------------------
        // Namespace resolution
        // -------------------------------------------------------------------------

        "throws BadRequestException when both namespaceId and namespaceExternalId are null" {
            shouldThrow<BadRequestException> {
                service.translateContent(
                    content = listOf("Hello"),
                    sourceLanguage = "en",
                    targetLanguage = "fr",
                    namespaceId = null,
                    namespaceExternalId = null,
                )
            }
        }

        "throws BadRequestException when no AI model is configured for namespace" {
            every { aiModelService.findAiModel(namespaceId) } returns null

            shouldThrow<BadRequestException> {
                service.translateContent(
                    content = listOf("Hello"),
                    sourceLanguage = "en",
                    targetLanguage = "fr",
                    namespaceId = namespaceId,
                    namespaceExternalId = null,
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // ChatClient factory helpers
    //
    // We build real ChatClient instances backed by a fake ChatModel rather than
    // mocking CallResponseSpec.content() -- that method returns String? and MockK
    // cannot infer the type parameter T for answers/returns on nullable types.
    // -------------------------------------------------------------------------

    /** Builds a [Generation] from a plain text string using the Spring AI 1.1.x constructor. */
    private fun generation(text: String): Generation = Generation(AssistantMessage(text), ChatGenerationMetadata.NULL)

    /**
     * A [ChatModel] that always returns a [ChatResponse] containing [text].
     */
    private fun fakeChatModel(text: String): ChatModel =
        ChatModel { _ ->
            ChatResponse(listOf(generation(text)))
        }

    /**
     * A [ChatModel] that returns each string in [responses] in order, then repeats
     * the last entry.
     */
    private fun fakeChatModelSequence(responses: MutableList<String>): ChatModel =
        ChatModel { _ ->
            val text = if (responses.size == 1) responses[0] else responses.removeFirst()
            ChatResponse(listOf(generation(text)))
        }

    /**
     * A [ChatModel] that always throws [ex].
     */
    private fun fakeChatModelThrowing(ex: Throwable): ChatModel = ChatModel { _ -> throw ex }

    private fun chatClientReturning(text: String): ChatClient = ChatClient.builder(fakeChatModel(text)).build()

    private fun chatClientReturningSequence(responses: MutableList<String>): ChatClient =
        ChatClient.builder(fakeChatModelSequence(responses)).build()

    private fun chatClientThrowing(ex: Throwable): ChatClient = ChatClient.builder(fakeChatModelThrowing(ex)).build()
}
