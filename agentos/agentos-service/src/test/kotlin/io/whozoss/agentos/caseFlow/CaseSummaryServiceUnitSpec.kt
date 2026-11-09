package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldHaveMaxLength
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.namespace.NamespaceCaseListUpdateBroadcaster
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
import org.springframework.ai.chat.prompt.Prompt
import java.util.UUID

class CaseSummaryServiceUnitSpec :
    StringSpec({

        val namespaceId: UUID = UUID.randomUUID()
        val providerId: UUID = UUID.randomUUID()
        val userActor = Actor(id = UUID.randomUUID().toString(), displayName = "User", role = ActorRole.USER)
        val agentActor = Actor(id = "agent", displayName = "Agent", role = ActorRole.AGENT)

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

        fun makeCase(summary: String? = null) =
            Case(
                metadata = EntityMetadata(),
                namespaceId = namespaceId,
                status = CaseStatus.IDLE,
                summary = summary,
                summaryMessageCount = summary?.let { 1 },
            )

        fun message(
            text: String,
            caseId: UUID,
            actor: Actor = userActor,
        ) = MessageEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            actor = actor,
            content = listOf(MessageContent.Text(text)),
        )

        /** Everything [CaseSummaryService] writes or announces, for the assertions. */
        class Fixture(
            case: Case,
            aiModelService: AiModelService = mockk { every { findAiModel(any()) } returns model },
            aiProviderService: AiProviderService = mockk { every { getById(any()) } returns provider },
            val chatClientProvider: ChatClientProvider = mockk(),
            storedCase: Case? = case,
        ) {
            val saved = mutableListOf<Case>()
            val broadcaster: NamespaceCaseListUpdateBroadcaster = mockk(relaxed = true)
            private val repository =
                mockk<CaseRepository> {
                    every { findByIds(any(), any()) } returns listOfNotNull(storedCase)
                    every { save(any()) } answers {
                        val c = firstArg<Case>()
                        saved.add(c)
                        c
                    }
                }
            val service = CaseSummaryService(aiModelService, aiProviderService, chatClientProvider, repository, broadcaster)
        }

        /** A [ChatClient] answering [answer] (null = no content) and capturing the prompt it receives. */
        fun stubbedChatClient(
            answer: String?,
            promptSlot: io.mockk.CapturingSlot<Prompt> = slot(),
        ): ChatClient {
            val chatClient = mockk<ChatClient>()
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { chatClient.prompt(capture(promptSlot)) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            if (answer != null) every { callSpec.content() } returns answer
            return chatClient
        }

        fun stubbedChatClientThrows(): ChatClient {
            val chatClient = mockk<ChatClient>()
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val callSpec = mockk<ChatClient.CallResponseSpec>()
            every { chatClient.prompt(any<Prompt>()) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } throws RuntimeException("Network error")
            return chatClient
        }

        fun ChatClientProvider.stub(chatClient: ChatClient) {
            every { getChatClient(any(), any(), any()) } returns chatClient
        }

        // -------------------------------------------------------------------------
        // Skipped silently: nothing is written, no fallback text
        // -------------------------------------------------------------------------

        "skips without a default model in the namespace, writing nothing" {
            val case = makeCase()
            val fixture =
                Fixture(case, aiModelService = mockk { every { findAiModel(any()) } returns null })

            fixture.service.summarizeCase(case, listOf(message("Hello", case.id)))

            fixture.saved shouldBe emptyList<Case>()
            verify(exactly = 0) { fixture.broadcaster.publishCaseUpdated(any()) }
        }

        "skips when the provider of the model cannot be found" {
            val case = makeCase()
            val fixture =
                Fixture(case, aiProviderService = mockk { every { getById(any()) } throws RuntimeException("gone") })

            fixture.service.summarizeCase(case, listOf(message("Hello", case.id)))

            fixture.saved shouldBe emptyList<Case>()
        }

        "skips when the case has no message text" {
            val case = makeCase()
            val fixture = Fixture(case)

            fixture.service.summarizeCase(case, emptyList())

            fixture.saved shouldBe emptyList<Case>()
        }

        "keeps the previous summary when the LLM call throws" {
            val case = makeCase(summary = "Old summary")
            val fixture = Fixture(case)
            fixture.chatClientProvider.stub(stubbedChatClientThrows())

            fixture.service.summarizeCase(case, listOf(message("Hello", case.id)))

            fixture.saved shouldBe emptyList<Case>()
            verify(exactly = 0) { fixture.broadcaster.publishCaseUpdated(any()) }
        }

        "writes nothing when the LLM answers blank" {
            val case = makeCase()
            val fixture = Fixture(case)
            fixture.chatClientProvider.stub(stubbedChatClient("   "))

            fixture.service.summarizeCase(case, listOf(message("Hello", case.id)))

            fixture.saved shouldBe emptyList<Case>()
        }

        "does not bring back a case deleted while the LLM call was in flight" {
            val case = makeCase()
            val fixture = Fixture(case, storedCase = null)
            fixture.chatClientProvider.stub(stubbedChatClient("A summary"))

            fixture.service.summarizeCase(case, listOf(message("Hello", case.id)))

            fixture.saved shouldBe emptyList<Case>()
        }

        // -------------------------------------------------------------------------
        // Happy path
        // -------------------------------------------------------------------------

        "saves the summary with the user message count it covers, and notifies the namespace" {
            val case = makeCase()
            val fixture = Fixture(case)
            fixture.chatClientProvider.stub(stubbedChatClient("  Migration of the billing service, in progress.  "))
            val events: List<CaseEvent> =
                listOf(
                    message("Help me migrate billing", case.id),
                    message("Sure, here is a plan", case.id, agentActor),
                    message("Start with step one", case.id),
                )

            fixture.service.summarizeCase(case, events)

            val saved = fixture.saved.single()
            saved.summary shouldBe "Migration of the billing service, in progress."
            saved.summaryMessageCount shouldBe 2
            saved.title shouldBe case.title
            verify(exactly = 1) { fixture.broadcaster.publishCaseUpdated(namespaceId) }
        }

        "caps the stored summary at 500 characters" {
            val case = makeCase()
            val fixture = Fixture(case)
            fixture.chatClientProvider.stub(stubbedChatClient("x".repeat(900)))

            fixture.service.summarizeCase(case, listOf(message("Hello", case.id)))

            fixture.saved.single().summary!! shouldHaveMaxLength 500
        }

        "uses the utility call: the default model with its reasoning effort capped at low" {
            val case = makeCase()
            val effortModel = model.copy(reasoningEffort = ReasoningEffort.MAX)
            val fixture = Fixture(case, aiModelService = mockk { every { findAiModel(any()) } returns effortModel })
            fixture.chatClientProvider.stub(stubbedChatClient("A summary"))

            fixture.service.summarizeCase(case, listOf(message("Hello", case.id)))

            verify(exactly = 1) {
                fixture.chatClientProvider.getChatClient(
                    effortModel.copy(reasoningEffort = ReasoningEffort.LOW),
                    provider,
                    case.id.toString(),
                )
            }
        }

        // -------------------------------------------------------------------------
        // Prompt input
        // -------------------------------------------------------------------------

        "feeds the previous summary and the agent answers to the model" {
            val case = makeCase(summary = "Billing migration planned.")
            val fixture = Fixture(case)
            val prompt = slot<Prompt>()
            fixture.chatClientProvider.stub(stubbedChatClient("Updated", prompt))

            fixture.service.summarizeCase(
                case,
                listOf(message("Go on", case.id), message("Done, step one finished", case.id, agentActor)),
            )

            val text = prompt.captured.contents
            text shouldContain "Previous summary: Billing migration planned."
            text shouldContain "User: Go on"
            text shouldContain "Agent: Done, step one finished"
        }

        "keeps only the last messages" {
            val case = makeCase()
            val events = (1..20).map { message("message-$it", case.id) }

            val transcript = CaseSummaryService.buildTranscript(null, events)!!

            transcript shouldNotContain "message-8\n"
            transcript shouldContain "User: message-9"
            transcript shouldContain "User: message-20"
            transcript.lines().size shouldBe CaseSummaryService.MAX_MESSAGES
        }

        "truncates each message and caps the whole input" {
            val case = makeCase()
            val events = (1..12).map { message("$it-" + "y".repeat(5_000), case.id) }

            val transcript = CaseSummaryService.buildTranscript("s".repeat(200), events)!!

            transcript shouldHaveMaxLength CaseSummaryService.MAX_INPUT_CHARS
            // the oldest messages are the ones dropped to fit
            transcript shouldContain "User: 12-"
            transcript shouldNotContain "User: 1-"
        }
    })
