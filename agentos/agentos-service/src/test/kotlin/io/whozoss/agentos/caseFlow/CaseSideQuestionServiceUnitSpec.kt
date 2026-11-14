package io.whozoss.agentos.caseFlow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class CaseSideQuestionServiceUnitSpec :
    StringSpec({

        val namespaceId: UUID = UUID.randomUUID()
        val providerId: UUID = UUID.randomUUID()
        val user = Actor(id = UUID.randomUUID().toString(), displayName = "Alice", role = ActorRole.USER)

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
                reasoningEffort = ReasoningEffort.HIGH,
            )
        val case = Case(metadata = EntityMetadata(), namespaceId = namespaceId, status = CaseStatus.RUNNING)

        fun message(text: String): CaseEvent =
            MessageEvent(
                namespaceId = namespaceId,
                caseId = case.id,
                actor = user,
                content = listOf(MessageContent.Text(text)),
            )

        class Fixture(
            storedCase: Case? = case,
            aiModel: AiModel? = model,
            events: List<CaseEvent> = listOf(message("Migrate the billing service")),
            providerLookup: () -> AiProvider = { provider },
        ) {
            val chatClientProvider: ChatClientProvider = mockk()
            val caseService: CaseService = mockk { every { findById(any(), any()) } returns storedCase }
            val caseEventService: CaseEventService = mockk { every { findByParent(any()) } returns events }
            val service =
                CaseSideQuestionService(
                    caseService,
                    caseEventService,
                    mockk<AiModelService> { every { findAiModel(any()) } returns aiModel },
                    mockk<AiProviderService> { every { getById(any()) } answers { providerLookup() } },
                    chatClientProvider,
                )
        }

        fun stubbedChatClient(
            answer: String?,
            promptSlot: io.mockk.CapturingSlot<Prompt> = slot(),
        ): ChatClient {
            val chatClient = mockk<ChatClient>()
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val callSpec = mockk<ChatClient.CallResponseSpec>()
            every { chatClient.prompt(capture(promptSlot)) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } returns answer
            return chatClient
        }

        fun throwingChatClient(): ChatClient {
            val chatClient = mockk<ChatClient>()
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val callSpec = mockk<ChatClient.CallResponseSpec>()
            every { chatClient.prompt(any<Prompt>()) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } throws RuntimeException("401 invalid api key sk-secret")
            return chatClient
        }

        fun ChatClientProvider.stub(chatClient: ChatClient) {
            every { getChatClient(any(), any(), any()) } returns chatClient
        }

        "answers from the transcript, trimmed, with a system message and the question after the transcript" {
            val fixture = Fixture()
            val prompt = slot<Prompt>()
            fixture.chatClientProvider.stub(stubbedChatClient("  It migrates billing.  ", prompt))

            val answer = fixture.service.answer(case.id, "What is this case about?")

            answer shouldBe "It migrates billing."
            val messages = prompt.captured.instructions
            messages.map { it.messageType } shouldBe listOf(MessageType.SYSTEM, MessageType.USER)
            messages[0].text shouldContain "no tools"
            messages[0].text shouldContain "does not contain the answer"
            val user = messages[1].text!!
            user shouldContain "User Alice: Migrate the billing service"
            user shouldContain "Side question: What is this case about?"
            (user.indexOf("Migrate the billing service") < user.indexOf("Side question")) shouldBe true
        }

        "uses the default model of the namespace as configured, without capping its reasoning effort" {
            val fixture = Fixture()
            fixture.chatClientProvider.stub(stubbedChatClient("ok"))

            fixture.service.answer(case.id, "anything?")

            verify(exactly = 1) { fixture.chatClientProvider.getChatClient(model, provider, case.id.toString()) }
        }

        "works on a case with no runtime and no message yet" {
            val fixture = Fixture(events = emptyList())
            val prompt = slot<Prompt>()
            fixture.chatClientProvider.stub(stubbedChatClient("Nothing was said yet.", prompt))

            fixture.service.answer(case.id, "What happened?") shouldBe "Nothing was said yet."

            prompt.captured.instructions[1].text!! shouldContain SideQuestionTranscript.EMPTY
        }

        "fails with not found when the case does not exist, without calling the model" {
            val fixture = Fixture(storedCase = null)

            shouldThrow<ResourceNotFoundException> { fixture.service.answer(UUID.randomUUID(), "?") }

            verify(exactly = 0) { fixture.chatClientProvider.getChatClient(any(), any(), any()) }
        }

        "fails with a readable conflict when the namespace has no default model" {
            val fixture = Fixture(aiModel = null)

            val error = shouldThrow<ConflictException> { fixture.service.answer(case.id, "?") }

            error.message!! shouldContain "No AI model is configured"
        }

        "fails with a readable conflict when the provider of the model cannot be found" {
            val fixture = Fixture(providerLookup = { throw RuntimeException("gone") })

            val error = shouldThrow<ConflictException> { fixture.service.answer(case.id, "?") }

            error.message!! shouldContain "provider"
        }

        "turns a provider failure into a 502 whose message carries nothing of the provider's error" {
            val fixture = Fixture()
            fixture.chatClientProvider.stub(throwingChatClient())

            val error = shouldThrow<ResponseStatusException> { fixture.service.answer(case.id, "?") }

            error.statusCode shouldBe HttpStatus.BAD_GATEWAY
            error.reason!! shouldNotContain "sk-secret"
            error.reason!! shouldNotContain "401"
        }

        "turns a blank or missing answer into a 502" {
            listOf("   ", null).forEach { blank ->
                val fixture = Fixture()
                fixture.chatClientProvider.stub(stubbedChatClient(blank))

                shouldThrow<ResponseStatusException> { fixture.service.answer(case.id, "?") }.statusCode shouldBe
                    HttpStatus.BAD_GATEWAY
            }
        }

        "persists nothing: the service has no write path into the case or its events" {
            val fixture = Fixture()
            fixture.chatClientProvider.stub(stubbedChatClient("ok"))

            fixture.service.answer(case.id, "?")

            // CaseService and CaseEventService are strict mocks that only stub the two reads: any write
            // (addMessage, create, emit...) would have failed the call above.
            verify(exactly = 1) { fixture.caseEventService.findByParent(case.id) }
            verify(exactly = 0) { fixture.caseService.addMessage(any(), any(), any(), any(), any()) }
        }
    })
