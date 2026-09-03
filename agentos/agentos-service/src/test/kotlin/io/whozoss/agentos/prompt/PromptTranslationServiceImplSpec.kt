package io.whozoss.agentos.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.data.forAll
import io.kotest.data.headers
import io.kotest.data.row
import io.kotest.data.table
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.ChatClientProvider
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
 *
 * Namespace resolution (externalId -> UUID) was removed from [PromptTranslationServiceImpl]
 * and moved to the controller layer, so these tests pass a resolved [UUID] directly.
 */
class PromptTranslationServiceImplSpec : StringSpec() {
    private val aiModelService = mockk<AiModelService>()
    private val aiProviderService = mockk<AiProviderService>()
    private val chatClientProvider = mockk<ChatClientProvider>()

    private val service =
        PromptTranslationServiceImpl(
            aiModelService = aiModelService,
            aiProviderService = aiProviderService,
            chatClientProvider = chatClientProvider,
            cacheProperties = PromptTranslationCacheProperties(),
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
        // translateContent — happy path
        // -------------------------------------------------------------------------

        "translateContent returns translated list with one entry per source element" {
            every { chatClientProvider.getChatClient(model, provider) } returns chatClientReturning("Bonjour")

            val result = service.translateContent(
                content = listOf("Hello"),
                sourceLanguage = "en",
                targetLanguage = "fr",
                namespaceId = namespaceId,
            )

            result shouldBe listOf("Bonjour")
        }

        "translateContent translates each element independently" {
            val responses = mutableListOf("Bonjour", "Au revoir")
            every { chatClientProvider.getChatClient(model, provider) } returns chatClientReturningSequence(responses)

            val result = service.translateContent(
                content = listOf("Hello", "Goodbye"),
                sourceLanguage = "en",
                targetLanguage = "fr",
                namespaceId = namespaceId,
            )

            result shouldBe listOf("Bonjour", "Au revoir")
        }

        // -------------------------------------------------------------------------
        // translateContent — fallback (data-driven)
        // -------------------------------------------------------------------------

        "translateContent falls back to original element on bad LLM response" {
            table(
                headers("scenario", "llmResponse"),
                row("blank",          ""),
                row("whitespace only", "   "),
            ).forAll { _, llmResponse ->
                every { chatClientProvider.getChatClient(model, provider) } returns chatClientReturning(llmResponse)
                service.translateContent(listOf("Hello"), "en", "fr", namespaceId) shouldBe listOf("Hello")
            }
        }

        "translateContent falls back to original element when LLM call throws" {
            every { chatClientProvider.getChatClient(model, provider) } returns
                chatClientThrowing(RuntimeException("LLM unavailable"))
            service.translateContent(listOf("Hello"), "en", "fr", namespaceId) shouldBe listOf("Hello")
        }

        // -------------------------------------------------------------------------
        // translateTitle — happy path
        // -------------------------------------------------------------------------

        "translateTitle returns translated string" {
            every { chatClientProvider.getChatClient(model, provider) } returns chatClientReturning("Revoir le profil")

            val result = service.translateTitle(
                title = "Review profile",
                sourceLanguage = "en",
                targetLanguage = "fr",
                namespaceId = namespaceId,
            )

            result shouldBe "Revoir le profil"
        }

        // -------------------------------------------------------------------------
        // translateTitle — fallback (data-driven)
        // -------------------------------------------------------------------------

        "translateTitle falls back to original on bad LLM response" {
            table(
                headers("scenario", "llmResponse"),
                row("blank",          ""),
                row("whitespace only", "   "),
            ).forAll { _, llmResponse ->
                every { chatClientProvider.getChatClient(model, provider) } returns chatClientReturning(llmResponse)
                service.translateTitle("Review profile", "en", "fr", namespaceId) shouldBe "Review profile"
            }
        }

        "translateTitle falls back to original when LLM call throws" {
            every { chatClientProvider.getChatClient(model, provider) } returns
                chatClientThrowing(RuntimeException("timeout"))
            service.translateTitle("Review profile", "en", "fr", namespaceId) shouldBe "Review profile"
        }

        // -------------------------------------------------------------------------
        // Model resolution
        // -------------------------------------------------------------------------

        "throws BadRequestException when no AI model is configured for namespace" {
            every { aiModelService.findAiModel(namespaceId) } returns null

            shouldThrow<BadRequestException> {
                service.translateContent(
                    content = listOf("Hello"),
                    sourceLanguage = "en",
                    targetLanguage = "fr",
                    namespaceId = namespaceId,
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

    /** A [ChatModel] that always returns a [ChatResponse] containing [text]. */
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

    /** A [ChatModel] that always throws [ex]. */
    private fun fakeChatModelThrowing(ex: Throwable): ChatModel = ChatModel { _ -> throw ex }

    private fun chatClientReturning(text: String): ChatClient = ChatClient.builder(fakeChatModel(text)).build()

    private fun chatClientReturningSequence(responses: MutableList<String>): ChatClient =
        ChatClient.builder(fakeChatModelSequence(responses)).build()

    private fun chatClientThrowing(ex: Throwable): ChatClient = ChatClient.builder(fakeChatModelThrowing(ex)).build()
}
