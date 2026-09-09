package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveMaxLength
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseUpdatedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.ai.chat.client.ChatClient
import java.util.UUID

class CaseNamingServiceUnitSpec :
    StringSpec({

        val namespaceId: UUID = UUID.randomUUID()
        val providerId: UUID = UUID.randomUUID()
        val userActor = Actor(id = UUID.randomUUID().toString(), displayName = "User", role = ActorRole.USER)

        val provider =
            AiProvider(
                metadata = EntityMetadata(id = providerId),
                namespaceId = namespaceId,
                name = "test-provider",
                apiType = AiApiType.OpenAI,
            )
        val model =
            AiModel(
                metadata = EntityMetadata(),
                aiProviderId = providerId,
                namespaceId = namespaceId,
                apiModelName = "test-model",
            )

        fun makeCase() =
            Case(
                metadata = EntityMetadata(),
                namespaceId = namespaceId,
                status = CaseStatus.IDLE,
            )

        fun messageEvent(
            text: String,
            caseId: UUID,
        ) = MessageEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            actor = userActor,
            content = listOf(MessageContent.Text(text)),
        )

        /**
         * Builds a [CaseNamingService]. The [caseRepository] mock records saved cases
         * in [savedCases] so tests can assert on the persisted title without fighting
         * MockK's generic type inference on [EntityRepository.save].
         */
        fun buildService(
            aiModelService: AiModelService = mockk { every { findAiModel(any()) } returns model },
            aiProviderService: AiProviderService = mockk { every { getById(any()) } returns provider },
            chatClientProvider: ChatClientProvider = mockk(),
            savedCases: MutableList<Case> = mutableListOf(),
        ): CaseNamingService {
            val caseRepository =
                mockk<CaseRepository> {
                    // reload-before-save: return empty so the ?: case fallback kicks in
                    every { findByIds(any(), any()) } returns emptyList()
                    every { save(any()) } answers {
                        val c = firstArg<Case>()
                        savedCases.add(c)
                        c
                    }
                }
            return CaseNamingService(aiModelService, aiProviderService, chatClientProvider, caseRepository)
        }

        /**
         * Builds a stubbed [ChatClient] where [ChatClient.ChatClientRequestSpec.call] returns a
         * [ChatClient.CallResponseSpec] whose [ChatClient.CallResponseSpec.content] throws [exception].
         *
         * Mirrors the pattern in [io.whozoss.agentos.agent.ConfirmationManagerSpec.stubChatClient]
         * to avoid MockK type-inference issues with the generic methods on [ChatClient.ChatClientRequestSpec].
         */
        fun stubbedChatClientThrows(exception: Throwable = RuntimeException("Network error")): ChatClient {
            val chatClient = mockk<ChatClient>()
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val callSpec = mockk<ChatClient.CallResponseSpec>()
            every { chatClient.prompt(any<org.springframework.ai.chat.prompt.Prompt>()) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } throws exception
            return chatClient
        }

        /**
         * Builds a stubbed [ChatClient] where [ChatClient.CallResponseSpec.content] returns [title]
         * (or null / blank to exercise fallback paths).
         *
         * Uses a relaxed [ChatClient.CallResponseSpec] mock so [ChatClient.CallResponseSpec.content]
         * returns null by default when [title] is null.
         */
        fun stubbedChatClient(title: String?): ChatClient {
            val chatClient = mockk<ChatClient>()
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { chatClient.prompt(any<org.springframework.ai.chat.prompt.Prompt>()) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            if (title != null) {
                every { callSpec.content() } returnsMany listOf(title)
            }
            return chatClient
        }

        /**
         * Stubs [ChatClientProvider.getChatClient] to return the given [chatClient].
         */
        fun ChatClientProvider.stub(chatClient: ChatClient) {
            every { getChatClient(any(), any(), any()) } returns chatClient
        }

        // -------------------------------------------------------------------------
        // Fallback: no model configured
        // -------------------------------------------------------------------------

        "uses first-message fallback when no AI model is configured in the namespace" {
            val aiModelService = mockk<AiModelService> { every { findAiModel(any()) } returns null }
            val saved = mutableListOf<Case>()
            val service = buildService(aiModelService = aiModelService, savedCases = saved)

            val case = makeCase()
            val events: List<CaseEvent> = listOf(messageEvent("Hello, I need help with Kotlin", case.id))
            val emitted = mutableListOf<CaseEvent>()

            service.nameCase(case, events) { emitted.add(it) }

            saved.single().title shouldBe "Hello, I need help with Kotlin"
            emitted.filterIsInstance<CaseUpdatedEvent>().single().title shouldBe "Hello, I need help with Kotlin"
        }

        "truncates fallback title to 50 characters when first message is long" {
            val longMessage = "a".repeat(80)
            val aiModelService = mockk<AiModelService> { every { findAiModel(any()) } returns null }
            val saved = mutableListOf<Case>()
            val service = buildService(aiModelService = aiModelService, savedCases = saved)

            val case = makeCase()
            val events: List<CaseEvent> = listOf(messageEvent(longMessage, case.id))
            val emitted = mutableListOf<CaseEvent>()

            service.nameCase(case, events) { emitted.add(it) }

            saved.single().title shouldHaveMaxLength 50
            saved.single().title shouldBe "a".repeat(50)
        }

        // -------------------------------------------------------------------------
        // Fallback: provider not found
        // -------------------------------------------------------------------------

        "uses first-message fallback when provider is not found" {
            val aiProviderService =
                mockk<AiProviderService> {
                    every { getById(any()) } throws RuntimeException("Provider not found")
                }
            val saved = mutableListOf<Case>()
            val service = buildService(aiProviderService = aiProviderService, savedCases = saved)

            val case = makeCase()
            val events: List<CaseEvent> = listOf(messageEvent("Debug my Spring Boot app", case.id))
            val emitted = mutableListOf<CaseEvent>()

            service.nameCase(case, events) { emitted.add(it) }

            saved.single().title shouldBe "Debug my Spring Boot app"
        }

        // -------------------------------------------------------------------------
        // Fallback: LLM call fails or returns blank
        // -------------------------------------------------------------------------

        "uses first-message fallback when LLM call throws" {
            val chatClientProvider = mockk<ChatClientProvider>()
            chatClientProvider.stub(stubbedChatClientThrows())
            val saved = mutableListOf<Case>()
            val service = buildService(chatClientProvider = chatClientProvider, savedCases = saved)

            val case = makeCase()
            val events: List<CaseEvent> = listOf(messageEvent("Review my pull request", case.id))
            val emitted = mutableListOf<CaseEvent>()

            service.nameCase(case, events) { emitted.add(it) }

            saved.single().title shouldBe "Review my pull request"
        }

        "uses first-message fallback when LLM returns null" {
            val chatClientProvider = mockk<ChatClientProvider>()
            chatClientProvider.stub(stubbedChatClient(null))
            val saved = mutableListOf<Case>()
            val service = buildService(chatClientProvider = chatClientProvider, savedCases = saved)

            val case = makeCase()
            val events: List<CaseEvent> = listOf(messageEvent("Explain coroutines", case.id))
            val emitted = mutableListOf<CaseEvent>()

            service.nameCase(case, events) { emitted.add(it) }

            saved.single().title shouldBe "Explain coroutines"
        }

        "uses first-message fallback when LLM returns blank string" {
            val chatClientProvider = mockk<ChatClientProvider>()
            chatClientProvider.stub(stubbedChatClient("   "))
            val saved = mutableListOf<Case>()
            val service = buildService(chatClientProvider = chatClientProvider, savedCases = saved)

            val case = makeCase()
            val events: List<CaseEvent> = listOf(messageEvent("Optimize my SQL query", case.id))
            val emitted = mutableListOf<CaseEvent>()

            service.nameCase(case, events) { emitted.add(it) }

            saved.single().title shouldBe "Optimize my SQL query"
        }

        // -------------------------------------------------------------------------
        // Happy path: LLM returns a title
        // -------------------------------------------------------------------------

        "uses LLM-generated title when available" {
            val chatClientProvider = mockk<ChatClientProvider>()
            chatClientProvider.stub(stubbedChatClient("Kotlin Coroutines Explained"))
            val saved = mutableListOf<Case>()
            val service = buildService(chatClientProvider = chatClientProvider, savedCases = saved)

            val case = makeCase()
            val events: List<CaseEvent> = listOf(messageEvent("Can you explain how coroutines work in Kotlin?", case.id))
            val emitted = mutableListOf<CaseEvent>()

            service.nameCase(case, events) { emitted.add(it) }

            saved.single().title shouldBe "Kotlin Coroutines Explained"
            emitted.filterIsInstance<CaseUpdatedEvent>().single().title shouldBe "Kotlin Coroutines Explained"
        }

        // -------------------------------------------------------------------------
        // Transcript: last N user messages are used
        // -------------------------------------------------------------------------

        "uses only the last MAX_USER_MESSAGES_FOR_NAMING user messages when more are present" {
            // With MAX_USER_MESSAGES_FOR_NAMING = 3, only the last 3 user messages feed
            // the LLM. We verify this indirectly: when the first two messages are the
            // only content, the transcript would be non-blank and the LLM would be called.
            // But when those two are excluded (beyond the takeLast window), the remaining
            // messages still produce a non-blank transcript — so the LLM IS called and
            // returns the stubbed title. The real assertion is on which messages form the
            // fallback: if the LLM fails we check the fallback is from the LAST messages.
            //
            // We test the window by making the first two messages the ONLY ones with text
            // and the last three empty — then the transcript is blank and nameCase skips.
            val chatClientProvider = mockk<ChatClientProvider>()
            chatClientProvider.stub(stubbedChatClient("Generated Title"))
            val saved = mutableListOf<Case>()
            val service = buildService(chatClientProvider = chatClientProvider, savedCases = saved)

            val case = makeCase()
            // Only the first two messages have text; the last three are empty.
            // takeLast(3) picks the last three (all empty) -> transcript is blank -> skip.
            val events: List<CaseEvent> = listOf(
                messageEvent("first old message", case.id),
                messageEvent("second old message", case.id),
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = case.id,
                    actor = userActor,
                    content = emptyList(), // no text
                ),
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = case.id,
                    actor = userActor,
                    content = emptyList(),
                ),
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = case.id,
                    actor = userActor,
                    content = emptyList(),
                ),
            )
            val emitted = mutableListOf<CaseEvent>()
            service.nameCase(case, events) { emitted.add(it) }

            // Transcript from the last 3 messages is blank -> nameCase must skip entirely
            saved shouldBe emptyList<Case>()
            emitted shouldBe emptyList<CaseEvent>()
        }

        // -------------------------------------------------------------------------
        // Edge cases
        // -------------------------------------------------------------------------

        "skips naming when events contain no user text content" {
            val saved = mutableListOf<Case>()
            val service = buildService(savedCases = saved)

            val case = makeCase()
            val events: List<CaseEvent> = emptyList()
            val emitted = mutableListOf<CaseEvent>()

            service.nameCase(case, events) { emitted.add(it) }

            saved shouldBe emptyList<Case>()
            emitted shouldBe emptyList<CaseEvent>()
        }

        "emits CaseUpdatedEvent with correct caseId and namespaceId" {
            val chatClientProvider = mockk<ChatClientProvider>()
            chatClientProvider.stub(stubbedChatClient("Generated Title"))
            val saved = mutableListOf<Case>()
            val service = buildService(chatClientProvider = chatClientProvider, savedCases = saved)

            val case = makeCase()
            val events: List<CaseEvent> = listOf(messageEvent("Some user message", case.id))
            val emitted = mutableListOf<CaseEvent>()

            service.nameCase(case, events) { emitted.add(it) }

            val updatedEvent = emitted.filterIsInstance<CaseUpdatedEvent>().single()
            updatedEvent.caseId shouldBe case.id
            updatedEvent.namespaceId shouldBe namespaceId
            updatedEvent.title shouldBe "Generated Title"
        }
    })
